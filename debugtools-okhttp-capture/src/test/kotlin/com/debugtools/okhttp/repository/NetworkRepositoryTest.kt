package com.debugtools.okhttp.repository

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.data.Timing
import com.debugtools.okhttp.data.WebSocketFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NetworkRepositoryTest {

    private fun httpRecord(id: String, ts: Long = 0L) = HttpRecord(
        id = id, timestamp = ts, method = "GET", url = "/",
        protocol = "HTTP/1.1", requestHeaders = emptyList(),
        requestBody = null, requestBodyTruncated = false,
        responseCode = 200, responseHeaders = emptyList(),
        responseBody = null, responseBodyTruncated = false,
        durationMs = 1L, timing = null
    )

    @Test fun `addHttp appends to snapshot`() {
        val repo = NetworkRepository(Config())
        repo.addHttp(httpRecord("a"))
        repo.addHttp(httpRecord("b"))
        assertEquals(listOf("a", "b"), repo.snapshot().httpRecords.map { it.id })
    }

    @Test fun `addHttp evicts oldest when at maxHttpRecords`() {
        val repo = NetworkRepository(Config(maxHttpRecords = 3))
        listOf("a", "b", "c", "d", "e").forEach { repo.addHttp(httpRecord(it)) }
        assertEquals(listOf("c", "d", "e"), repo.snapshot().httpRecords.map { it.id })
    }

    @Test fun `attachTiming targets the record by id not url`() {
        val repo = NetworkRepository(Config())
        repo.addHttp(httpRecord("id1")) // both have url "/"
        repo.addHttp(httpRecord("id2"))
        repo.attachTiming("id1", Timing(null, null, null, null, null, null, totalMs = 42L))
        val recs = repo.snapshot().httpRecords
        assertEquals(42L, recs.first { it.id == "id1" }.timing?.totalMs)
        assertNull("same-URL sibling must not receive the timing", recs.first { it.id == "id2" }.timing)
    }

    @Test fun `openSession creates session`() {
        val repo = NetworkRepository(Config())
        repo.openSession("sess-1", "wss://x", "handshake-1", openedAt = 100L)
        val s = repo.snapshot().webSocketSessions.single()
        assertEquals("sess-1", s.sessionId)
        assertEquals(100L, s.openedAt)
        assertNull(s.closedAt)
    }

    @Test fun `openSession evicts oldest session when at limit`() {
        val repo = NetworkRepository(Config(maxWebSocketSessions = 2))
        listOf("a", "b", "c").forEach {
            repo.openSession(it, "wss://x", "h-$it", openedAt = 0L)
        }
        assertEquals(listOf("b", "c"), repo.snapshot().webSocketSessions.map { it.sessionId })
    }

    @Test fun `addFrame appends to session`() {
        val repo = NetworkRepository(Config())
        repo.openSession("s1", "wss://x", "h", 0L)
        repo.addFrame("s1", Direction.SEND, FrameType.TEXT, "hello".toByteArray(), 0L)
        val s = repo.snapshot().webSocketSessions.single()
        assertEquals(1, s.frames.size)
        assertEquals(Direction.SEND, s.frames[0].direction)
        assertEquals("hello", String(s.frames[0].payload!!))
    }

    @Test fun `addFrame evicts oldest frame within session at limit`() {
        val repo = NetworkRepository(Config(maxFramesPerSession = 2))
        repo.openSession("s1", "wss://x", "h", 0L)
        repo.addFrame("s1", Direction.SEND, FrameType.TEXT, "1".toByteArray(), 1L)
        repo.addFrame("s1", Direction.SEND, FrameType.TEXT, "2".toByteArray(), 2L)
        repo.addFrame("s1", Direction.SEND, FrameType.TEXT, "3".toByteArray(), 3L)
        val frames = repo.snapshot().webSocketSessions.single().frames
        assertEquals(2, frames.size)
        assertEquals(listOf("2", "3"), frames.map { String(it.payload!!) })
    }

    @Test fun `addFrame truncates payload above maxFrameBytes`() {
        val repo = NetworkRepository(Config(maxFrameBytes = 10))
        repo.openSession("s1", "wss://x", "h", 0L)
        val payload = ByteArray(100) { it.toByte() }
        repo.addFrame("s1", Direction.SEND, FrameType.BINARY, payload, 0L)
        val frame = repo.snapshot().webSocketSessions.single().frames.single()
        assertEquals(100, frame.size)         // original size preserved
        assertEquals(10, frame.payload!!.size) // truncated body
        assertTrue(frame.truncated)
    }

    @Test fun `closeSession sets closedAt and closeCode`() {
        val repo = NetworkRepository(Config())
        repo.openSession("s1", "wss://x", "h", 0L)
        repo.closeSession("s1", code = 1000, reason = "NORMAL", closedAt = 200L)
        val s = repo.snapshot().webSocketSessions.single()
        assertEquals(200L, s.closedAt)
        assertEquals(1000, s.closeCode)
        assertEquals("NORMAL", s.closeReason)
    }

    @Test fun `state flow emits on changes`() = runTest {
        val repo = NetworkRepository(Config())
        val first = repo.state.value
        repo.addHttp(httpRecord("a"))
        val second = repo.state.value
        assertNotEquals(first, second)
        assertEquals(1, second.httpRecords.size)
    }

    @Test fun `addFrame on unknown session is no-op`() {
        val repo = NetworkRepository(Config())
        // Session doesn't exist
        repo.addFrame("missing", Direction.SEND, FrameType.TEXT, byteArrayOf(), 0L)
        assertTrue(repo.snapshot().webSocketSessions.isEmpty())
    }
}
