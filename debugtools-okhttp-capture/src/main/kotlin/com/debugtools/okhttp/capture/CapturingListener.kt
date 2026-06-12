package com.debugtools.okhttp.capture

import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Wraps the business [WebSocketListener] to record receive-direction frames
 * and lifecycle events to [NetworkRepository], then delegates to the original.
 */
internal class CapturingListener(
    private val delegate: WebSocketListener,
    private val repository: NetworkRepository,
    private val sessionId: String
) : WebSocketListener() {

    override fun onOpen(webSocket: WebSocket, response: Response) {
        delegate.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        repository.addFrame(
            sessionId, Direction.RECEIVE, FrameType.TEXT,
            text.toByteArray(), System.currentTimeMillis()
        )
        delegate.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        repository.addFrame(
            sessionId, Direction.RECEIVE, FrameType.BINARY,
            bytes.toByteArray(), System.currentTimeMillis()
        )
        delegate.onMessage(webSocket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        delegate.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        repository.closeSession(sessionId, code, reason, System.currentTimeMillis())
        delegate.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        repository.failSession(
            sessionId,
            "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}",
            System.currentTimeMillis()
        )
        delegate.onFailure(webSocket, t, response)
    }
}
