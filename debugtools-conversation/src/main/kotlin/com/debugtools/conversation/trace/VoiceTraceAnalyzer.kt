package com.debugtools.conversation.trace

data class VoiceTimelineItem(
    val id: String,
    val label: String,
    val sourceName: String,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,
    val durationMs: Long?,
    val includeInDuration: Boolean,
    val category: TraceCategory,
    val order: Int
)

data class AnalyzedVoiceRequest(
    val requestId: String,
    val rawEvents: List<VoiceTraceEvent>,
    val timelineItems: List<VoiceTimelineItem>,
    val performanceDurationMs: Long,
    val issues: List<TraceIssue>
)

class VoiceTraceAnalyzer(private val profile: VoiceTraceProfile) {
    fun analyze(requestId: String, events: List<VoiceTraceEvent>): AnalyzedVoiceRequest {
        val sorted = events.sortedBy { it.timestampUptimeMs }
        val items = mutableListOf<VoiceTimelineItem>()
        val issues = mutableListOf<TraceIssue>()

        addMarkers(sorted, items, issues)
        addStages(sorted, items, issues)
        addErrorEvents(sorted, issues)

        val ordered = items.sortedWith(compareBy<VoiceTimelineItem> { it.order }.thenBy { it.startUptimeMs })
        val performanceDurationMs = ordered.filter { it.includeInDuration }.sumOf { it.durationMs ?: 0L }
        return AnalyzedVoiceRequest(
            requestId = requestId,
            rawEvents = sorted,
            timelineItems = ordered,
            performanceDurationMs = performanceDurationMs,
            issues = issues
        )
    }

    private fun addMarkers(
        events: List<VoiceTraceEvent>,
        items: MutableList<VoiceTimelineItem>,
        issues: MutableList<TraceIssue>
    ) {
        for (marker in profile.markerRules) {
            if (!marker.showInConversation) continue
            events.filter { it.name == marker.name }.forEach { event ->
                items += VoiceTimelineItem(
                    id = marker.name,
                    label = marker.label,
                    sourceName = marker.name,
                    startUptimeMs = event.timestampUptimeMs,
                    endUptimeMs = null,
                    durationMs = null,
                    includeInDuration = marker.includeInDuration,
                    category = marker.category,
                    order = marker.order
                )
                if (!marker.includeInDuration) {
                    issues += TraceIssue(
                        severity = TraceIssueSeverity.INFO,
                        type = "MARKER_NOT_COUNTED",
                        detail = "${marker.label} is displayed but excluded from duration",
                        stageId = marker.name
                    )
                }
            }
        }
    }

    private fun addStages(
        events: List<VoiceTraceEvent>,
        items: MutableList<VoiceTimelineItem>,
        issues: MutableList<TraceIssue>
    ) {
        for (stage in profile.stageRules) {
            val begin = events.firstOrNull { it.name == stage.begin }
            val end = events.firstOrNull { event ->
                event.name == stage.end && begin != null && event.timestampUptimeMs >= begin.timestampUptimeMs
            }
            if (begin == null || end == null) {
                if (stage.required) {
                    issues += TraceIssue(
                        severity = TraceIssueSeverity.CRITICAL,
                        type = "REQUIRED_STAGE_MISSING",
                        detail = "${stage.label} is missing begin or end",
                        stageId = stage.id
                    )
                }
                continue
            }

            val duration = end.timestampUptimeMs - begin.timestampUptimeMs
            if (stage.showInConversation) {
                items += VoiceTimelineItem(
                    id = stage.id,
                    label = stage.label,
                    sourceName = stage.begin,
                    startUptimeMs = begin.timestampUptimeMs,
                    endUptimeMs = end.timestampUptimeMs,
                    durationMs = duration,
                    includeInDuration = stage.includeInDuration,
                    category = stage.category,
                    order = stage.order
                )
            }

            val warnIfSlowMs = stage.warnIfSlowMs
            if (stage.includeInDuration && warnIfSlowMs != null && duration > warnIfSlowMs) {
                issues += TraceIssue(
                    severity = TraceIssueSeverity.WARNING,
                    type = "SLOW_STAGE",
                    detail = "${stage.label} took ${duration}ms > ${warnIfSlowMs}ms",
                    stageId = stage.id
                )
            }
        }
    }

    private fun addErrorEvents(events: List<VoiceTraceEvent>, issues: MutableList<TraceIssue>) {
        events.filter { it.type == TraceEventType.ERROR }.forEach { event ->
            issues += TraceIssue(
                severity = TraceIssueSeverity.CRITICAL,
                type = "ERROR_EVENT",
                detail = "${event.name} reported error"
            )
        }
    }
}
