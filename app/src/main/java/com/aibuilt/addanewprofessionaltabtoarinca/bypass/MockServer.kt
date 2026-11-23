package com.aibuilt.addanewprofessionaltabtoarinca.bypass

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * A hand-rolled HTTP stub. It answers every path with plausible JSON so a screen whose
 * backend is dead still renders and the patched state can be seen.
 */
class MockServer(
    private val log: suspend (String) -> Unit,
    private val onRequest: suspend (String) -> Unit
) {

    private var job: Job? = null
    private var socket: ServerSocket? = null

    val running: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, port: Int, canned: Map<String, String> = emptyMap()) {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(port)
                socket = server
                log("mock server listening on http://127.0.0.1:$port")
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: continue
                    launch(Dispatchers.IO) {
                        runCatching {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val requestLine = reader.readLine().orEmpty()
                            val path = requestLine.split(' ').getOrNull(1) ?: "/"
                            onRequest(requestLine)
                            val body = canned[path]
                                ?: canned.entries.firstOrNull { path.contains(it.key) }?.value
                                ?: defaultBody(path)
                            val bytes = body.toByteArray()
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            client.getOutputStream().write(header.toByteArray())
                            client.getOutputStream().write(bytes)
                            client.getOutputStream().flush()
                        }.also { runCatching { client.close() } }
                    }
                }
            } catch (t: Throwable) {
                log("mock server stopped: ${t.message}")
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        job?.cancel()
        job = null
        socket = null
    }

    companion object {
        const val DEFAULT_PORT = 8787

        fun defaultBody(path: String): String = when {
            path.contains("config") ->
                """{"premium":true,"isPremium":true,"plan":"PRO","isAdult":true,"forceUpdate":false,"minVersionCode":1,"ads":false}"""
            path.contains("entitle") || path.contains("subscri") || path.contains("purchase") ->
                """{"active":true,"entitled":true,"isPremium":true,"plan":"PRO","expiresAt":4102444800000}"""
            path.contains("user") || path.contains("me") || path.contains("profile") ->
                """{"id":"local","name":"Local user","premium":true,"isAdult":true,"token":"local-token","expiry":4102444800000}"""
            path.contains("token") || path.contains("auth") ->
                """{"token":"local-token","expiresIn":999999,"isPremium":true}"""
            else -> """{"ok":true,"premium":true,"active":true,"isPremium":true}"""
        }
    }
}
