package com.debugtools.okhttp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NetworkCaptureModuleTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `moduleId and tabTitle are set`() {
        val m = NetworkCaptureModule.create()
        assertEquals("debugtools_okhttp_capture", m.moduleId)
        assertEquals("网络抓包", m.tabTitle)
    }

    @Test fun `httpInterceptor captures HTTP requests`() {
        val m = NetworkCaptureModule.create()
        val client = OkHttpClient.Builder().addInterceptor(m.httpInterceptor()).build()
        server.enqueue(MockResponse().setBody("hi"))
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
        assertEquals(1, m.repositoryForTest().snapshot().httpRecords.size)
    }

    @Test fun `newWebSocket records send and receive frames`() {
        val m = NetworkCaptureModule.create()
        val client = OkHttpClient.Builder().addInterceptor(m.httpInterceptor()).build()

        val received = CountDownLatch(1)
        val serverListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("pong:$text")
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val clientListener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { received.countDown() }
        }
        val ws = m.newWebSocket(
            client, Request.Builder().url(server.url("/ws")).build(), clientListener
        )
        ws.send("hello")
        assertTrue("server response received", received.await(3, TimeUnit.SECONDS))
        ws.close(1000, "bye")

        val session = m.repositoryForTest().snapshot().webSocketSessions.single()
        assertTrue("send frame recorded", session.frames.any { frame -> frame.payload?.toString(Charsets.UTF_8) == "hello" })
        assertTrue("receive frame recorded", session.frames.any { frame -> frame.payload?.toString(Charsets.UTF_8) == "pong:hello" })
    }
}
