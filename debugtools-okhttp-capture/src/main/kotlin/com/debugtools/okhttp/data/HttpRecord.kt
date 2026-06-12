package com.debugtools.okhttp.data

data class HttpRecord(
    val id: String,
    val timestamp: Long,
    val method: String,
    val url: String,
    val protocol: String,
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: ByteArray?,
    val requestBodyTruncated: Boolean,
    val responseCode: Int,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: ByteArray?,
    val responseBodyTruncated: Boolean,
    val durationMs: Long,
    val timing: Timing?,
    val failure: String? = null,
    val isWebSocketUpgrade: Boolean = false,
    val webSocketSessionId: String? = null
) {
    // Data class equals on ByteArray uses identity; override to value-equality for tests
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRecord) return false
        return id == other.id &&
            timestamp == other.timestamp &&
            method == other.method &&
            url == other.url &&
            protocol == other.protocol &&
            requestHeaders == other.requestHeaders &&
            (requestBody?.contentEquals(other.requestBody) ?: (other.requestBody == null)) &&
            requestBodyTruncated == other.requestBodyTruncated &&
            responseCode == other.responseCode &&
            responseHeaders == other.responseHeaders &&
            (responseBody?.contentEquals(other.responseBody) ?: (other.responseBody == null)) &&
            responseBodyTruncated == other.responseBodyTruncated &&
            durationMs == other.durationMs &&
            timing == other.timing &&
            failure == other.failure &&
            isWebSocketUpgrade == other.isWebSocketUpgrade &&
            webSocketSessionId == other.webSocketSessionId
    }

    override fun hashCode(): Int = id.hashCode()
}
