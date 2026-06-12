package com.debugtools.okhttp.capture

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CapturingListenerTest {

    private lateinit var repo: NetworkRepository
    private val sessionId = "sess-1"
    private val url = "wss://example.com/"

    private val mockWebSocket = object : WebSocket {
        override fun request(): Request = Request.Builder().url(url).build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {}
    }

    @Before fun setUp() {
        repo = NetworkRepository(Config())
        repo.openSession(sessionId, url, "handshake-1", openedAt = 0L)
    }

    @Test fun `onOpen delegates`() {
        var delegateCalled = false
        val delegate = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { delegateCalled = true }
        }
        val listener = CapturingListener(delegate, repo, sessionId)
        listener.onOpen(mockWebSocket, dummyResponse())
        assertTrue(delegateCalled)
    }

    @Test fun `onMessage TEXT records RECEIVE frame and delegates`() {
        var delegateText: String? = null
        val delegate = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { delegateText = text }
        }
        val listener = CapturingListener(delegate, repo, sessionId)
        listener.onMessage(mockWebSocket, "hello")
        assertEquals("hello", delegateText)

        val frame = repo.snapshot().webSocketSessions.single().frames.single()
        assertEquals(Direction.RECEIVE, frame.direction)
        assertEquals(FrameType.TEXT, frame.type)
        assertEquals("hello", String(frame.payload!!))
    }

    @Test fun `onMessage BINARY records frame and delegates`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        var delegateBytes: ByteString? = null
        val delegate = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { delegateBytes = bytes }
        }
        val listener = CapturingListener(delegate, repo, sessionId)
        listener.onMessage(mockWebSocket, payload.toByteString())
        assertNotNull(delegateBytes)

        val frame = repo.snapshot().webSocketSessions.single().frames.single()
        assertEquals(Direction.RECEIVE, frame.direction)
        assertEquals(FrameType.BINARY, frame.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test fun `onClosed records close code and reason`() {
        val delegate = object : WebSocketListener() {}
        val listener = CapturingListener(delegate, repo, sessionId)
        listener.onClosed(mockWebSocket, 1000, "NORMAL")
        val session = repo.snapshot().webSocketSessions.single()
        assertEquals(1000, session.closeCode)
        assertEquals("NORMAL", session.closeReason)
        assertNotNull(session.closedAt)
    }

    @Test fun `onFailure records error`() {
        val delegate = object : WebSocketListener() {}
        val listener = CapturingListener(delegate, repo, sessionId)
        listener.onFailure(mockWebSocket, RuntimeException("boom"), null)
        val session = repo.snapshot().webSocketSessions.single()
        assertTrue(session.failure!!.contains("boom"))
    }

    private fun dummyResponse(): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()
}
