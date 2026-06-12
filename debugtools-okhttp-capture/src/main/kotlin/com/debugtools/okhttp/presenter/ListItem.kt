package com.debugtools.okhttp.presenter

import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.data.WebSocketFrame
import com.debugtools.okhttp.data.WebSocketSession

/**
 * Flattened RecyclerView model. The list is built by [NetworkCapturePresenter]
 * by interleaving HTTP records and WS sessions in time order.
 *
 * A WS session is rendered as a header row followed by zero or more frame rows
 * when expanded (default: expanded since traffic is low for car business protocols).
 */
sealed class ListItem {
    abstract val timestamp: Long
    abstract val id: String

    data class HttpRow(val record: HttpRecord) : ListItem() {
        override val timestamp: Long get() = record.timestamp
        override val id: String get() = "http:${record.id}"
    }

    data class WebSocketSessionRow(
        val session: WebSocketSession,
        val expanded: Boolean
    ) : ListItem() {
        override val timestamp: Long get() = session.openedAt
        override val id: String get() = "ws:${session.sessionId}"
    }

    data class WebSocketFrameRow(val frame: WebSocketFrame) : ListItem() {
        override val timestamp: Long get() = frame.timestamp
        override val id: String get() = "frame:${frame.sessionId}:${frame.timestamp}:${frame.direction}"
    }
}
