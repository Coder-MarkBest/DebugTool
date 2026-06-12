package com.debugtools.okhttp.capture

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CapturingWebSocketTest {

    private lateinit var repo: NetworkRepository
    private val sessionId = "sess-1"

    private val sendReturn = arrayOf(true)  // mutable to flip per test
    private var lastSentText: String? = null
    private var lastSentBytes: ByteString? = null
    private var canceled = false
    private var closeCalled = false

    private val delegate = object : WebSocket {
        override fun request(): Request = Request.Builder().url("wss://example.com").build()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { lastSentText = text; return sendReturn[0] }
        override fun send(bytes: ByteString): Boolean { lastSentBytes = bytes; return sendReturn[0] }
        override fun close(code: Int, reason: String?): Boolean { closeCalled = true; return true }
        override fun cancel() { canceled = true }
    }

    @Before fun setUp() {
        repo = NetworkRepository(Config())
        repo.openSession(sessionId, "wss://example.com", "h", 0L)
        sendReturn[0] = true
        lastSentText = null; lastSentBytes = null; canceled = false; closeCalled = false
    }

    @Test fun `send TEXT records SEND frame and delegates`() {
        val ws = CapturingWebSocket(delegate, repo, sessionId)
        val accepted = ws.send("hello")
        assertTrue(accepted)
        assertEquals("hello", lastSentText)

        val frame = repo.snapshot().webSocketSessions.single().frames.single()
        assertEquals(Direction.SEND, frame.direction)
        assertEquals(FrameType.TEXT, frame.type)
        assertEquals("hello", String(frame.payload!!))
    }

    @Test fun `send BINARY records SEND frame and delegates`() {
        val ws = CapturingWebSocket(delegate, repo, sessionId)
        val payload = byteArrayOf(0x01, 0x02)
        val accepted = ws.send(payload.toByteString())
        assertTrue(accepted)
        assertNotNull(lastSentBytes)

        val frame = repo.snapshot().webSocketSessions.single().frames.single()
        assertEquals(FrameType.BINARY, frame.type)
        assertArrayEquals(payload, frame.payload)
    }

    @Test fun `send not accepted does NOT record frame`() {
        sendReturn[0] = false
        val ws = CapturingWebSocket(delegate, repo, sessionId)
        ws.send("rejected")
        assertTrue(repo.snapshot().webSocketSessions.single().frames.isEmpty())
    }

    @Test fun `close delegates`() {
        val ws = CapturingWebSocket(delegate, repo, sessionId)
        ws.close(1000, "bye")
        assertTrue(closeCalled)
    }

    @Test fun `cancel delegates`() {
        val ws = CapturingWebSocket(delegate, repo, sessionId)
        ws.cancel()
        assertTrue(canceled)
    }
}
