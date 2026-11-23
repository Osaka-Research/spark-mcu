package com.osakaresearch.spark.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiMessage(val role: String, val content: String, val imageDataUrl: String? = null) {
    companion object {
        fun system(text: String) = AiMessage("system", text)
        fun user(text: String, imageDataUrl: String? = null) = AiMessage("user", text, imageDataUrl)
        fun assistant(text: String) = AiMessage("assistant", text)
    }
}

data class AiConfig(
    val provider: AiProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val maxTokens: Int = 4096,
    val temperature: Double? = null,
)

sealed class AiResult {
    data class Ok(val text: String) : AiResult()
    data class Err(val code: Int, val reason: String) : AiResult()
}

data class AiModel(val slug: String, val displayName: String)

data class AiToolCall(val namespace: String, val name: String, val callId: String, val arguments: JSONObject)

/**
 * Single-shot chat call. Blocking network work is forced to IO.
 *
 * Shape dispatch is done once here — [AiProvider.shape] decides which body
 * format + auth header we emit. OpenAI-shape covers OpenAI, Minimax and any
 * compatible gateway via a swapped base URL.
 */
object AiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun complete(cfg: AiConfig, messages: List<AiMessage>): AiResult =
        withContext(Dispatchers.IO) {
            if (cfg.apiKey.isBlank()) return@withContext AiResult.Err(0, "missing api key")
            if (cfg.baseUrl.isBlank()) return@withContext AiResult.Err(0, "missing base url")
            if (cfg.model.isBlank()) return@withContext AiResult.Err(0, "missing model")
            when (cfg.provider.shape) {
                Shape.OpenAI -> callOpenAi(cfg, messages)
                Shape.Anthropic -> callAnthropic(cfg, messages)
                Shape.Responses -> callResponses(cfg, messages)
            }
        }

    suspend fun listChatGptModels(accessToken: String): List<AiModel> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        http.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Model list failed (${response.code}): ${raw.take(240)}")
            val models = JSONObject(raw).optJSONArray("models") ?: JSONArray()
            (0 until models.length()).mapNotNull { index ->
                models.optJSONObject(index)?.let { item ->
                    val slug = item.optString("slug").takeIf(String::isNotBlank) ?: return@let null
                    if (item.optString("visibility") != "list") return@let null
                    AiModel(slug, item.optString("display_name").ifBlank { slug })
                }
            }
        }
    }

    suspend fun completeWithTools(
        cfg: AiConfig,
        messages: List<AiMessage>,
        toolDefinitions: JSONArray,
        onTextDelta: (String) -> Unit = {},
        onRound: (Int) -> Unit = {},
        onProgress: (String) -> Unit = {},
        executeTool: suspend (AiToolCall) -> String,
    ): AiResult = withContext(Dispatchers.IO) {
        if (cfg.provider.shape != Shape.Responses) return@withContext AiResult.Err(0, "tool use requires ChatGPT plan sign-in")
        if (cfg.apiKey.isBlank() || cfg.model.isBlank()) return@withContext AiResult.Err(0, "ChatGPT account and model are required")

        val instructions = messages.filter { it.role == "system" || it.role == "developer" }
            .joinToString("\n\n") { it.content }.ifBlank { null }
        val input = JSONArray().apply {
            messages.filter { it.role != "system" && it.role != "developer" }.forEach { message ->
                val content = if (message.imageDataUrl.isNullOrBlank()) message.content else JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", message.content))
                    .put(JSONObject().put("type", "input_image").put("image_url", message.imageDataUrl))
                put(JSONObject().put("role", message.role).put("content", content))
            }
        }

        AgentToolLoop().run(input,
            request = { requestResponses(cfg, instructions, it, toolDefinitions, onTextDelta) },
            execute = executeTool, onRound = onRound, onProgress = onProgress)
    }

    private suspend fun callResponses(cfg: AiConfig, messages: List<AiMessage>): AiResult {
        val instructions = messages.filter { it.role == "system" || it.role == "developer" }
            .joinToString("\n\n") { it.content }.ifBlank { null }
        val input = JSONArray().apply {
            messages.filter { it.role != "system" && it.role != "developer" }.forEach { message ->
                val content = if (message.imageDataUrl.isNullOrBlank()) {
                    message.content
                } else {
                    JSONArray()
                        .put(JSONObject().put("type", "input_text").put("text", message.content))
                        .put(JSONObject().put("type", "input_image").put("image_url", message.imageDataUrl))
                }
                put(JSONObject().put("role", message.role).put("content", content))
            }
        }
        val turn = requestResponses(cfg, instructions, input, null)
        turn.error?.let { return it }
        if (turn.calls.isNotEmpty()) return AiResult.Err(0, "This provider request returned tools that Spark cannot run.")
        return if (turn.text.isBlank()) AiResult.Err(0, "ChatGPT returned no text") else AiResult.Ok(turn.text)
    }

    internal data class ResponseTurn(
        val text: String,
        val outputItems: JSONArray,
        val calls: List<AiToolCall>,
        val error: AiResult.Err?,
    )

    private suspend fun requestResponses(
        cfg: AiConfig,
        instructions: String?,
        input: JSONArray,
        tools: JSONArray?,
        onTextDelta: (String) -> Unit = {},
    ): ResponseTurn {
        val body = JSONObject().apply {
            put("model", cfg.model)
            instructions?.let { put("instructions", it) }
            put("input", input)
            put("store", false)
            put("stream", true)
            put("include", JSONArray().put("reasoning.encrypted_content"))
            tools?.let { put("tools", it); put("tool_choice", "auto"); put("parallel_tool_calls", false) }
        }
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resume(ResponseTurn("", JSONArray(), emptyList(), AiResult.Err(0, error.message ?: "Connection failed")))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            if (!response.isSuccessful) {
                                ResponseTurn("", JSONArray(), emptyList(), AiResult.Err(response.code, response.body?.string().orEmpty().take(400)))
                            } else {
                                val source = response.body?.source()
                                if (source == null) ResponseTurn("", JSONArray(), emptyList(), AiResult.Err(response.code, "Empty response stream"))
                                else readResponsesStream(source) { delta -> if (continuation.isActive) onTextDelta(delta) }
                            }
                        }
                    } catch (error: Exception) {
                        ResponseTurn("", JSONArray(), emptyList(), AiResult.Err(0, error.message ?: "Stream failed"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    internal fun readResponsesStream(source: BufferedSource, onTextDelta: (String) -> Unit = {}): ResponseTurn {
        val text = StringBuilder()
        var completed = false
        var outputItems = JSONArray()
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val event = try { JSONObject(data) } catch (_: Throwable) { continue }
            when (event.optString("type")) {
                "response.output_text.delta" -> {
                    val delta = event.optString("delta")
                    text.append(delta)
                    onTextDelta(delta)
                }
                "response.completed" -> {
                    completed = true
                    val response = event.optJSONObject("response")
                    if (text.isEmpty()) text.append(extractResponseText(response))
                    outputItems = response?.optJSONArray("output") ?: JSONArray()
                }
                "response.failed", "error" -> {
                    val responseError = event.optJSONObject("response")?.optJSONObject("error")
                        ?: event.optJSONObject("error")
                    return ResponseTurn("", JSONArray(), emptyList(), AiResult.Err(
                        0,
                        responseError?.optString("message")?.takeIf(String::isNotBlank)
                            ?: "ChatGPT request failed (${responseError?.optString("code") ?: "unknown_error"})",
                    ))
                }
                "response.incomplete" -> return ResponseTurn("", JSONArray(), emptyList(), AiResult.Err(0, "ChatGPT response was incomplete"))
            }
        }
        if (!completed) return ResponseTurn("", JSONArray(), emptyList(), AiResult.Err(0, "ChatGPT stream ended before response.completed"))
        val calls = buildList {
            for (i in 0 until outputItems.length()) {
                val item = outputItems.optJSONObject(i) ?: continue
                if (item.optString("type") == "function_call") {
                    val args = runCatching { JSONObject(item.getString("arguments")) }
                        .getOrElse { return ResponseTurn("", outputItems, emptyList(), AiResult.Err(422, "Model returned invalid tool arguments; no actions from this response were run")) }
                    add(AiToolCall(
                        namespace = item.optString("namespace"),
                        name = item.optString("name"),
                        callId = item.optString("call_id"),
                        arguments = args,
                    ))
                }
            }
        }
        return ResponseTurn(text.toString(), outputItems, calls, null)
    }

    private fun extractResponseText(response: JSONObject?): String = buildString {
        val output = response?.optJSONArray("output") ?: return@buildString
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") append(part.optString("text"))
            }
        }
    }

    private fun callOpenAi(cfg: AiConfig, messages: List<AiMessage>): AiResult {
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
            put("max_tokens", cfg.maxTokens)
            cfg.temperature?.let { put("temperature", it) }
        }
        val req = Request.Builder()
            .url("${cfg.baseUrl.trimEnd('/')}/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(req) { json ->
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun callAnthropic(cfg: AiConfig, messages: List<AiMessage>): AiResult {
        // Anthropic splits system out of the messages array.
        val system = messages.firstOrNull { it.role == "system" }?.content
        val turns = messages.filter { it.role != "system" }
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("max_tokens", cfg.maxTokens)
            if (!system.isNullOrBlank()) put("system", system)
            cfg.temperature?.let { put("temperature", it) }
            put("messages", JSONArray().apply {
                turns.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
        }
        val req = Request.Builder()
            .url("${cfg.baseUrl.trimEnd('/')}/v1/messages")
            .addHeader("x-api-key", cfg.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(req) { json ->
            val arr = json.getJSONArray("content")
            buildString {
                for (i in 0 until arr.length()) {
                    val part = arr.getJSONObject(i)
                    if (part.optString("type") == "text") append(part.optString("text"))
                }
            }
        }
    }

    private inline fun execute(req: Request, extract: (JSONObject) -> String): AiResult =
        try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return AiResult.Err(resp.code, raw.take(400))
                val text = extract(JSONObject(raw))
                if (text.isBlank()) AiResult.Err(resp.code, "empty response") else AiResult.Ok(text)
            }
        } catch (t: Throwable) {
            AiResult.Err(0, t.message ?: t.javaClass.simpleName)
        }

}
