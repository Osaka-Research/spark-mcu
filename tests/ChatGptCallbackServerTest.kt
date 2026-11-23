package com.osakaresearch.spark.ai

import java.net.Socket
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Run with the production ChatGptCallbackServer.kt using kotlinc, then run this main. */
fun main() {
    val redirect = "http://127.0.0.1:1455/auth/callback"
    val good = "$redirect?code=test-code&state=test-state&client_id=test-client&iss=https%3A%2F%2Fauth.openai.com"
    check(parseCallback(good, redirect, "test-state").code == "test-code")
    check(parseCallback("$redirect?error=access_denied&state=test-state", redirect, "test-state").error == "access_denied")
    val rejected = listOf(
        good.replace("state=test-state", "state=wrong"),
        good.replace("127.0.0.1", "localhost"),
        good.replace(":1455", ":1456"),
        good.replace("/auth/callback", "/other"),
        good.replace("https%3A%2F%2Fauth.openai.com", "https%3A%2F%2Fexample.com"),
        "$good&state=duplicate",
        "$good#fragment",
        "$redirect?state=test-state",
    )
    rejected.forEach { url -> check(runCatching { parseCallback(url, redirect, "test-state") }.isFailure) }
    println("PASS: callback validation, denial, stale state, wrong endpoint/issuer, duplicate fields")

    val executor = Executors.newSingleThreadExecutor()
    try {
        ChatGptCallbackServer(0).use { server ->
            val port = URI(server.redirectUri).port
            val result = executor.submit(Callable { server.await("test-state", 8_000) {} })
            // Reproduce Chrome opening a TCP connection without an HTTP request first.
            Socket("127.0.0.1", port).use {
                check(request(port, "/favicon.ico").startsWith("HTTP/1.1 400"))
            }
            check(request(port, "/auth/callback?code=stale&state=wrong").startsWith("HTTP/1.1 400"))
            check(request(port, "/auth/callback?state=test-state").startsWith("HTTP/1.1 400"))
            // A partial request times out without terminating the server.
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("GET /auth/".toByteArray())
                check(request(port, "/auth/callback?code=ok&state=test-state").startsWith("HTTP/1.1 200"))
            }
            check(result.get(4, TimeUnit.SECONDS).contains("code=ok"))
            println("PASS: idle preconnect, partial request, favicon and stale callback do not stop sign-in")
        }
        ChatGptCallbackServer(0).use { server ->
            val failure = runCatching { server.await("test-state", 100) {} }.exceptionOrNull()
            check(failure?.message?.contains("timed out") == true)
            println("PASS: sign-in deadline gives a retry message")
        }
        val server = ChatGptCallbackServer(0)
        val port = URI(server.redirectUri).port
        val cancelled = AtomicBoolean(false)
        val finished = executor.submit(Callable {
            server.use {
                runCatching { server.await("test-state", 8_000) {
                    if (cancelled.get()) throw CancellationException()
                } }.exceptionOrNull()
            }
        })
        cancelled.set(true)
        check(finished.get(2, TimeUnit.SECONDS) is CancellationException)
        ChatGptCallbackServer(port).close()
        println("PASS: cancellation stops waiting and releases the callback port")
    } finally {
        executor.shutdownNow()
    }
}

private fun request(port: Int, path: String): String = Socket("127.0.0.1", port).use { socket ->
    socket.soTimeout = 4_000
    socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
    socket.getInputStream().bufferedReader().readText()
}
