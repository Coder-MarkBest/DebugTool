package com.debugtools.okhttp.data

import org.junit.Assert.*
import org.junit.Test

class DataModelsTest {

    @Test fun `Direction enum has SEND and RECEIVE`() {
        assertEquals(2, Direction.values().size)
        assertNotNull(Direction.SEND)
        assertNotNull(Direction.RECEIVE)
    }

    @Test fun `FrameType enum has TEXT BINARY PING PONG CLOSE`() {
        assertEquals(5, FrameType.values().size)
        assertNotNull(FrameType.TEXT)
        assertNotNull(FrameType.BINARY)
        assertNotNull(FrameType.PING)
        assertNotNull(FrameType.PONG)
        assertNotNull(FrameType.CLOSE)
    }

    @Test fun `HttpRecord equality is value-based`() {
        val a = HttpRecord(
            id = "1", timestamp = 100L, method = "GET", url = "/", protocol = "HTTP/1.1",
            requestHeaders = emptyList(), requestBody = null, requestBodyTruncated = false,
            responseCode = 200, responseHeaders = emptyList(), responseBody = null, responseBodyTruncated = false,
            durationMs = 50L, timing = null, failure = null, isWebSocketUpgrade = false, webSocketSessionId = null
        )
        val b = a.copy()
        assertEquals(a, b)
    }

    @Test fun `WebSocketFrame stores all fields`() {
        val f = WebSocketFrame(
            sessionId = "sess-1", timestamp = 100L, direction = Direction.SEND,
            type = FrameType.TEXT, size = 5, payload = "hello".toByteArray(), truncated = false
        )
        assertEquals("sess-1", f.sessionId)
        assertEquals(Direction.SEND, f.direction)
        assertEquals(5, f.size)
        assertFalse(f.truncated)
    }

    @Test fun `WebSocketSession is mutable for closedAt and frames`() {
        val s = WebSocketSession(
            sessionId = "sess-1", url = "wss://example", handshakeRecordId = "h-1",
            openedAt = 100L, frames = mutableListOf()
        )
        assertNull(s.closedAt)
        s.closedAt = 200L
        assertEquals(200L, s.closedAt)
        s.frames.add(WebSocketFrame("sess-1", 110L, Direction.SEND, FrameType.TEXT, 1, byteArrayOf(1), false))
        assertEquals(1, s.frames.size)
    }

    @Test fun `Timing total is required even when phase values null`() {
        val t = Timing(dnsMs = null, connectMs = null, tlsMs = null,
            requestSendMs = null, waitMs = null, responseReceiveMs = null, totalMs = 127L)
        assertEquals(127L, t.totalMs)
        assertNull(t.dnsMs)
    }
}
