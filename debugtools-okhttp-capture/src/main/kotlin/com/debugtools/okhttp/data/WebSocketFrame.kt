package com.debugtools.okhttp.data

data class WebSocketFrame(
    val sessionId: String,
    val timestamp: Long,
    val direction: Direction,
    val type: FrameType,
    val size: Int,
    val payload: ByteArray?,
    val truncated: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WebSocketFrame) return false
        return sessionId == other.sessionId &&
            timestamp == other.timestamp &&
            direction == other.direction &&
            type == other.type &&
            size == other.size &&
            (payload?.contentEquals(other.payload) ?: (other.payload == null)) &&
            truncated == other.truncated
    }

    override fun hashCode(): Int =
        31 * sessionId.hashCode() + timestamp.hashCode()
}
