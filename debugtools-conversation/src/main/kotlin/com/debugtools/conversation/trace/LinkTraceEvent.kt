package com.debugtools.conversation.trace

data class LinkTraceEvent(
    val traceId: String?,
    val name: String,
    val type: TraceEventType,
    val timestampUptimeMs: Long,
    val wallTimeMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, String> = emptyMap()
) {
    internal fun toVoiceTraceEvent() = VoiceTraceEvent(
        requestId = traceId,
        name = name,
        type = type,
        timestampUptimeMs = timestampUptimeMs,
        wallTimeMs = wallTimeMs,
        attributes = attributes
    )
}
