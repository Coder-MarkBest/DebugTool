package com.debugtools.sample

import com.debugtools.okhttp.NetworkCaptureModule
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Generates self-contained sample network traffic against an in-process
 * [MockWebServer], all flowing through the capturing [client] so it shows up in
 * the DebugTools 「网络抓包」 panel. No internet / external endpoint needed.
 *
 * One [generate] call fires a varied batch: 200 JSON, POST with body, 404, 500,
 * a slow request, plus a short WebSocket session.
 *
 * Call [generate] off the main thread (it starts a local socket server).
 */
class SampleTrafficGenerator(
    private val client: OkHttpClient,
    private val captureModule: NetworkCaptureModule,
    private val log: (String) -> Unit
) {
    private var server: MockWebServer? = null

    private fun ensureServer(): MockWebServer {
        server?.let { return it }
        return MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: ""
                    return when {
                        path.startsWith("/ws") -> MockResponse().withWebSocketUpgrade(EchoServer)
                        path == "/users" -> json(200, """{"users":[{"id":1,"name":"小爱"},{"id":2,"name":"小度"}],"total":2}""")
                        path == "/login" -> json(200, """{"token":"eyJhbGciOiJIUzI1NiJ9.demo","expiresIn":3600}""")
                        path == "/missing" -> json(404, """{"error":"resource not found","code":404}""")
                        path == "/boom" -> json(500, """{"error":"internal server error","traceId":"abc-123"}""")
                        path == "/slow" -> json(200, """{"ok":true,"note":"served after 800ms"}""")
                            .setBodyDelay(800, TimeUnit.MILLISECONDS)
                        else -> json(200, """{"ok":true}""")
                    }
                }
            }
            start()
            server = this
        }
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    /** Fire one varied batch. Must be called off the main thread. */
    fun generate() {
        val s = ensureServer()

        fun http(method: String, path: String, jsonBody: String? = null) {
            val body = jsonBody?.toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url(s.url(path)).method(method, body).build()
            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = log("✗ $method $path: ${e.message}")
                override fun onResponse(call: Call, response: Response) {
                    val code = response.code
                    response.close()
                    log("✓ $method $path → $code")
                }
            })
        }

        http("GET", "/users")
        http("POST", "/login", """{"user":"demo","pwd":"123456"}""")
        http("GET", "/missing")
        http("GET", "/boom")
        http("GET", "/slow")

        // WebSocket session through the capturing factory.
        val wsReq = Request.Builder().url(s.url("/ws")).build()
        captureModule.newWebSocket(client, wsReq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"hello","seq":1}""")
                webSocket.send("""{"type":"ping","seq":2}""")
                webSocket.send("""{"type":"bye","seq":3}""")
                webSocket.close(1000, "done")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = log("← WS $text")
        })

        log("已生成示例流量：5 个 HTTP + 1 个 WebSocket — 切到「网络抓包」查看")
    }

    fun shutdown() {
        try { server?.shutdown() } catch (_: Exception) { /* ignore */ }
        server = null
    }

    /** Server side of the demo WebSocket: echo whatever the client sends. */
    private object EchoServer : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.send("echo: $text")
        }
    }
}
