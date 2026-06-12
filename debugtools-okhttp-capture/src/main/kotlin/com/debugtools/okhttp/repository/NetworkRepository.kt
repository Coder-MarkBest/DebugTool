package com.debugtools.okhttp.repository

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.data.WebSocketFrame
import com.debugtools.okhttp.data.WebSocketSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory storage for captured HTTP records and WebSocket sessions.
 *
 * Thread-safe: all mutation methods are @Synchronized. The [state] StateFlow is
 * updated atomically after each mutation; consumers see a consistent immutable snapshot.
 *
 * Eviction policy: LRU (oldest at head, newest appended). When [Config] limits are hit,
 * the head element is dropped.
 */
class NetworkRepository(private val config: Config) {

    /** Immutable snapshot consumed by the UI. */
    data class Snapshot(
        val httpRecords: List<HttpRecord> = emptyList(),
        val webSocketSessions: List<WebSocketSession> = emptyList()
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    private val httpRecords = ArrayDeque<HttpRecord>()
    private val sessions = LinkedHashMap<String, WebSocketSession>() // preserves insertion order

    @Synchronized
    fun addHttp(record: HttpRecord) {
        if (httpRecords.size >= config.maxHttpRecords) {
            httpRecords.removeFirst()
        }
        httpRecords.addLast(record)
        publish()
    }

    @Synchronized
    fun openSession(
        sessionId: String,
        url: String,
        handshakeRecordId: String,
        openedAt: Long
    ) {
        if (sessions.size >= config.maxWebSocketSessions) {
            val firstKey = sessions.keys.first()
            sessions.remove(firstKey)
        }
        sessions[sessionId] = WebSocketSession(
            sessionId = sessionId,
            url = url,
            handshakeRecordId = handshakeRecordId,
            openedAt = openedAt
        )
        publish()
    }

    @Synchronized
    fun addFrame(
        sessionId: String,
        direction: Direction,
        type: FrameType,
        payload: ByteArray,
        timestamp: Long
    ) {
        val session = sessions[sessionId] ?: return
        val originalSize = payload.size
        val truncated = originalSize > config.maxFrameBytes
        val stored = if (truncated) payload.copyOfRange(0, config.maxFrameBytes) else payload

        if (session.frames.size >= config.maxFramesPerSession) {
            session.frames.removeAt(0)
        }
        session.frames.add(
            WebSocketFrame(
                sessionId = sessionId,
                timestamp = timestamp,
                direction = direction,
                type = type,
                size = originalSize,
                payload = stored,
                truncated = truncated
            )
        )
        publish()
    }

    @Synchronized
    fun closeSession(sessionId: String, code: Int, reason: String?, closedAt: Long) {
        val session = sessions[sessionId] ?: return
        session.closedAt = closedAt
        session.closeCode = code
        session.closeReason = reason
        publish()
    }

    @Synchronized
    fun failSession(sessionId: String, error: String, closedAt: Long) {
        val session = sessions[sessionId] ?: return
        session.failure = error
        session.closedAt = closedAt
        publish()
    }

    @Synchronized
    fun snapshot(): Snapshot = _state.value

    @Synchronized
    fun clear() {
        httpRecords.clear()
        sessions.clear()
        publish()
    }

    /**
     * Attach timing data to the most recent HttpRecord matching the given URL.
     * Called post-hoc by TimingEventListener after callEnd/callFailed.
     */
    @Synchronized
    fun attachTimingByUrl(url: String, timing: com.debugtools.okhttp.data.Timing) {
        val index = httpRecords.indexOfLast { it.url == url && it.timing == null }
        if (index >= 0) {
            val record = httpRecords[index]
            httpRecords[index] = record.copy(timing = timing)
            publish()
        }
    }

    private fun publish() {
        _state.value = Snapshot(
            httpRecords = httpRecords.toList(),
            webSocketSessions = sessions.values.map { it.copy(frames = it.frames.toMutableList()) }
        )
    }
}
