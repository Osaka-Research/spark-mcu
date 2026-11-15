package com.osakaresearch.spark.mcp

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Local MCP server. Binds to 127.0.0.1 only — never 0.0.0.0 — so it isn't
 * reachable from the LAN and can't be port-scanned from the same WiFi.
 * Single endpoint, `POST /mcp`, speaking JSON-RPC 2.0. Three methods:
 * `initialize`, `tools/list`, `tools/call`. Everything else returns
 * MethodNotFound.
 *
 * Auth: `Authorization: Bearer <token>` matched in constant time against
 * the token minted on first launch by [McpTokenStore]. The token is available
 * from the in-app status card.
 *
 * Streaming / SSE is deliberately out of scope for v1 — every call is
 * request/response. Compile is seconds, flash ~15 s; MCP clients hold the
 * request long enough.
 */
object McpServer {
    private const val TAG = "SparkMcp"
    private const val PORT = 8847
    private const val PROTOCOL_VERSION = "2024-11-05"
    private const val SERVER_NAME = "spark-mcu"
    private const val SERVER_VERSION = "0.1.0"

    @Volatile private var http: NanoImpl? = null

    fun start(ctx: Context) {
        if (http != null) return
        val app = ctx.applicationContext
        val tokenStore = McpTokenStore(app)
        val bridge = McpBridge(app)
        val tools = McpTools(bridge)
        tokenStore.tokenOrMint()

        val impl = NanoImpl(PORT, tokenStore, tools)
        impl.start(NanoHTTPD.SOCKET_READ_TIMEOUT, /* daemon = */ true)
        http = impl
        Log.i(TAG, "MCP endpoint ready: http://127.0.0.1:$PORT/mcp")
    }

    fun stop() {
        http?.stop()
        http = null
    }

    private class NanoImpl(
        port: Int,
        private val tokens: McpTokenStore,
        private val tools: McpTools,
    ) : NanoHTTPD(InetAddress.getByName("127.0.0.1").hostAddress, port) {

        override fun serve(session: IHTTPSession): Response {
            if (session.method != Method.POST || session.uri != "/mcp") {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }

            val auth = session.headers["authorization"]
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
            if (!tokens.matches(auth)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, "application/json",
                    """{"error":"unauthorized"}""",
                )
            }

            val body = readBody(session)
            val req = try { JSONObject(body) } catch (t: Throwable) {
                return rpcError(null, -32700, "parse error: ${t.message}")
            }

            val id: Any? = if (req.has("id")) req.get("id") else null
            val method = req.optString("method")
            val params = req.optJSONObject("params") ?: JSONObject()

            return try {
                when (method) {
                    "initialize" -> rpcResult(id, handleInitialize())
                    "notifications/initialized" -> newFixedLengthResponse(
                        Response.Status.NO_CONTENT, "text/plain", ""
                    )
                    "tools/list" -> rpcResult(id, JSONObject().put("tools", tools.catalog()))
                    "tools/call" -> rpcResult(id, handleToolsCall(params))
                    "ping" -> rpcResult(id, JSONObject())
                    else -> rpcError(id, -32601, "method not found: $method")
                }
            } catch (e: ToolError) {
                rpcError(id, e.code, e.message ?: "tool error")
            } catch (t: Throwable) {
                Log.w(TAG, "internal error on $method", t)
                rpcError(id, -32603, "internal error: ${t.message}")
            }
        }

        private fun handleInitialize(): JSONObject = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JSONObject().put("tools", JSONObject()))
            put("serverInfo", JSONObject()
                .put("name", SERVER_NAME)
                .put("version", SERVER_VERSION))
        }

        private fun handleToolsCall(params: JSONObject): JSONObject {
            val name = params.optString("name")
            if (name.isBlank()) throw ToolError(-32602, "missing tool name")
            val args = params.optJSONObject("arguments") ?: JSONObject()
            val text = try {
                runBlocking { tools.invoke(name, args) }
            } catch (e: ToolError) {
                return toolResult("${e.code}: ${e.message ?: "tool error"}", true)
            }
            val failed = runCatching { JSONObject(text).optBoolean("ok", true) == false }
                .getOrDefault(false)
            return toolResult(text, failed)
        }

        private fun toolResult(text: String, failed: Boolean): JSONObject {
            val content = JSONArray().put(
                JSONObject().put("type", "text").put("text", text)
            )
            return JSONObject().put("content", content).put("isError", failed)
        }

        private fun rpcResult(id: Any?, result: JSONObject): Response {
            val env = JSONObject().put("jsonrpc", "2.0").put("result", result)
            if (id != null) env.put("id", id) else env.put("id", JSONObject.NULL)
            return newFixedLengthResponse(Response.Status.OK, "application/json", env.toString())
        }

        private fun rpcError(id: Any?, code: Int, message: String): Response {
            val err = JSONObject().put("code", code).put("message", message)
            val env = JSONObject().put("jsonrpc", "2.0").put("error", err)
            if (id != null) env.put("id", id) else env.put("id", JSONObject.NULL)
            return newFixedLengthResponse(Response.Status.OK, "application/json", env.toString())
        }

        private fun readBody(session: IHTTPSession): String {
            // NanoHTTPD's parseBody stuffs POST bodies into this map under
            // "postData". `files` is populated as a side effect of calling it.
            val files = HashMap<String, String>()
            session.parseBody(files)
            return files["postData"] ?: session.queryParameterString ?: ""
        }
    }
}
