package com.osakaresearch.spark.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AiMessage(val role: String, val content: String) {
    companion object {
        fun system(text: String) = AiMessage("system", text)
        fun user(text: String) = AiMessage("user", text)
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
