package com.osakaresearch.spark.ai

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A browser may open an idle/preconnect socket before sending the real redirect. */
internal class ChatGptCallbackServer(port: Int = 1455) : Closeable {
    private val server = ServerSocket().apply {
        try {
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
            soTimeout = 500
        } catch (e: Exception) {
            close()
            throw e
        }
    }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"

    fun await(state: String, timeoutMillis: Long, checkActive: () -> Unit): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            checkActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            val callback = socket.use {
                try {
                    socket.soTimeout = 1_000
                    val request = readHeaders(socket, checkActive) ?: return@use null
                    val parts = request.lineSequence().first().trimEnd('\r').split(' ', limit = 3)
                    if (parts.size != 3 || parts[0] != "GET" || !parts[1].startsWith("/")) {
                        respond(socket, 400, "Invalid request. Return to Spark MCU and try again.")
                        return@use null
                    }
                    val url = "http://127.0.0.1:${server.localPort}${parts[1]}"
                    try {
                        parseCallback(url, redirectUri, state)
                    } catch (_: IllegalArgumentException) {
                        respond(socket, 400, "This link does not match the current sign-in. Return to Spark MCU.")
                        return@use null
                    }
                    // The authorization code is still usable if the browser closes this socket.
                    runCatching { respond(socket, 200, "Sign-in received. Return to Spark MCU to finish connecting ChatGPT.") }
                    url
                } catch (_: IOException) {
                    // Idle sockets, browser retries, and disconnected clients must not end login.
                    null
                }
            }
            if (callback != null) return callback
        }
        error("ChatGPT sign-in timed out. Tap Continue with ChatGPT to try again.")
    }

    private fun readHeaders(socket: Socket, checkActive: () -> Unit): String? {
        val input = socket.getInputStream()
        val request = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (request.length < 16_384 && System.nanoTime() < deadline) {
            checkActive()
            val next = input.read()
            if (next < 0) return null
            request.append(next.toChar())
            if (request.endsWith("\r\n\r\n")) return request.toString()
        }
        return null
    }

    private fun respond(socket: Socket, status: Int, message: String) {
        val body = ("<!doctype html><meta charset=utf-8>" +
            "<meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>Spark MCU</title><h2>Spark MCU</h2><p>$message</p>").toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    override fun close() = server.close()
}

internal data class ChatGptCallback(val code: String?, val error: String?, val clientId: String?)

internal fun parseCallback(url: String, redirectUri: String, state: String): ChatGptCallback {
    val uri = try { URI(url.trim()) } catch (_: Exception) {
        throw IllegalArgumentException("Paste the complete callback address from the browser.")
    }
    val expected = URI(redirectUri)
    require(uri.scheme == expected.scheme && uri.host == expected.host && uri.port == expected.port &&
        uri.rawPath == expected.rawPath && uri.rawUserInfo == null && uri.rawFragment == null) {
        "Paste the callback address for this sign-in from the browser."
    }
    val fields = mutableMapOf<String, String>()
    for (field in uri.rawQuery.orEmpty().split('&')) {
        val pair = field.split('=', limit = 2)
        val key = URLDecoder.decode(pair[0], "UTF-8")
        val value = URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        require(fields.put(key, value) == null) { "The callback contains duplicate fields." }
    }
    require(state.isNotBlank() && MessageDigest.isEqual(
        state.toByteArray(Charsets.UTF_8), fields["state"].orEmpty().toByteArray(Charsets.UTF_8),
    )) { "This callback belongs to a different sign-in. Use the latest browser address." }
    require(fields["iss"] == null || fields["iss"] == "https://auth.openai.com") {
        "The callback has an unexpected issuer."
    }
    require(!fields["code"].isNullOrBlank() || !fields["error"].isNullOrBlank()) {
        "The callback has no sign-in result."
    }
    return ChatGptCallback(fields["code"], fields["error"], fields["client_id"])
}
