package com.osakaresearch.spark.net

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class SparkConnState {
    data object Disconnected : SparkConnState()
    data class Connecting(val url: String) : SparkConnState()
    data class Connected(val url: String) : SparkConnState()
    data class Failed(val reason: String) : SparkConnState()
}

object SparkClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private val connectionCounter = java.util.concurrent.atomic.AtomicLong()
    val connectionVersion: Long get() = connectionCounter.get()
    @Volatile var latestBoardHello: JSONObject? = null
        private set

    private val _state = MutableStateFlow<SparkConnState>(SparkConnState.Disconnected)
    val state: StateFlow<SparkConnState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    fun connect(host: String, port: Int = 81) {
        disconnect()
        connectionCounter.incrementAndGet()
        latestBoardHello = null
        val url = "ws://$host:$port/"
        _state.value = SparkConnState.Connecting(url)
        val req = Request.Builder().url(url).build()
        socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = SparkConnState.Connected(url)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("evt") == "hello" }
                    ?.let { latestBoardHello = it }
                _incoming.tryEmit(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = SparkConnState.Failed(t.message ?: "unknown")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = SparkConnState.Disconnected
            }
        })
    }

    fun disconnect() {
        connectionCounter.incrementAndGet()
        socket?.close(1000, "bye")
        socket = null
        latestBoardHello = null
        _state.value = SparkConnState.Disconnected
    }

    fun send(json: String): Boolean = socket?.send(json) ?: false
}
