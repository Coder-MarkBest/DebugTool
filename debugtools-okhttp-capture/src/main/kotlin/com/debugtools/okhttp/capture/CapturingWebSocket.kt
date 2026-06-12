package com.debugtools.okhttp.capture

import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString

/**
 * Wraps the OkHttp [WebSocket] returned to business code so that all `send()`
 * calls are recorded to [NetworkRepository] before delegating to the real socket.
 *
 * Only records when `delegate.send()` returns true (false = queue full, message dropped).
 */
internal class CapturingWebSocket(
    private val delegate: WebSocket,
    private val repository: NetworkRepository,
    private val sessionId: String
) : WebSocket {

    override fun request(): Request = delegate.request()
    override fun queueSize(): Long = delegate.queueSize()

    override fun send(text: String): Boolean {
        val accepted = delegate.send(text)
        if (accepted) {
            repository.addFrame(
                sessionId, Direction.SEND, FrameType.TEXT,
                text.toByteArray(), System.currentTimeMillis()
            )
        }
        return accepted
    }

    override fun send(bytes: ByteString): Boolean {
        val accepted = delegate.send(bytes)
        if (accepted) {
            repository.addFrame(
                sessionId, Direction.SEND, FrameType.BINARY,
                bytes.toByteArray(), System.currentTimeMillis()
            )
        }
        return accepted
    }

    override fun close(code: Int, reason: String?): Boolean = delegate.close(code, reason)
    override fun cancel() = delegate.cancel()
}
