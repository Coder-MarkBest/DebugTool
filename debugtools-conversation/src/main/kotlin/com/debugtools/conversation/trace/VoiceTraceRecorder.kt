package com.debugtools.conversation.trace

data class VoiceTraceSnapshot(
    val eventsByRequest: Map<String, List<VoiceTraceEvent>>,
    val orphanEvents: List<VoiceTraceEvent>,
    val activeRequestIds: Set<String>,
    val closedRequestIds: Set<String>,
    val issues: List<TraceIssue>
)

class VoiceTraceRecorder(private val profile: VoiceTraceProfile) {
    private val lock = Any()
    private val eventsByRequest = linkedMapOf<String, MutableList<VoiceTraceEvent>>()
    private val orphanEvents = mutableListOf<VoiceTraceEvent>()
    private val active = linkedSetOf<String>()
    private val closed = linkedSetOf<String>()
    private val lastActiveAt = hashMapOf<String, Long>()
    private val issues = mutableListOf<TraceIssue>()

    fun record(event: VoiceTraceEvent) = synchronized(lock) {
        val requestId = event.requestId
        if (requestId != null) {
            eventsByRequest.getOrPut(requestId) { mutableListOf() }.add(event)
            active += requestId
            lastActiveAt[requestId] = event.timestampUptimeMs
            if (profile.isExitEvent(event.name)) close(requestId)
            return@synchronized
        }

        if (profile.isExitEvent(event.name)) {
            closeWithoutRequestId(event)
        } else {
            orphanEvents += event
        }
    }

    fun snapshot(): VoiceTraceSnapshot = synchronized(lock) {
        VoiceTraceSnapshot(
            eventsByRequest = eventsByRequest.mapValues { it.value.toList() },
            orphanEvents = orphanEvents.toList(),
            activeRequestIds = active.toSet(),
            closedRequestIds = closed.toSet(),
            issues = issues.toList()
        )
    }

    private fun close(requestId: String) {
        active -= requestId
        closed += requestId
    }

    private fun closeWithoutRequestId(event: VoiceTraceEvent) {
        when (active.size) {
            0 -> {
                orphanEvents += event
                issues += TraceIssue(
                    severity = TraceIssueSeverity.WARNING,
                    type = "ORPHAN_EXIT_EVENT",
                    detail = "${event.name} had no active request"
                )
            }
            1 -> close(active.first())
            else -> {
                val latest = active.maxBy { lastActiveAt[it] ?: Long.MIN_VALUE }
                close(latest)
                issues += TraceIssue(
                    severity = TraceIssueSeverity.WARNING,
                    type = "EXIT_MATCHED_BY_LATEST_ACTIVE",
                    detail = "${event.name} had no requestId; matched $latest"
                )
            }
        }
    }
}
