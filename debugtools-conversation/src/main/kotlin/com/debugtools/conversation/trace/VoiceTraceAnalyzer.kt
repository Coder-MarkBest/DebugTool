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
    val graphNodes: List<TraceGraphNode>,
    val graphEdges: List<TraceGraphEdge>,
    val performanceDurationMs: Long,
    val issues: List<TraceIssue>
)

data class TraceGraphNode(
    val eventName: String,
    val label: String,
    val timestampUptimeMs: Long,
    val type: TraceEventType,
    val category: TraceCategory,
    val ruleId: String?,
    val attributes: Map<String, String>
)

data class TraceGraphEdge(
    val fromEventName: String,
    val toEventName: String,
    val durationMs: Long
)

class VoiceTraceAnalyzer(private val profile: VoiceTraceProfile) {
    private data class StageMatch(
        val stage: StageRule,
        val begin: VoiceTraceEvent,
        val end: VoiceTraceEvent
    )

    fun analyze(requestId: String, events: List<VoiceTraceEvent>): AnalyzedVoiceRequest {
        val sorted = events.sortedBy { it.timestampUptimeMs }
        val stageMatches = stageMatches(sorted)
        val items = mutableListOf<VoiceTimelineItem>()
        val issues = mutableListOf<TraceIssue>()

        addMarkers(sorted, items, issues)
        addStages(stageMatches, items, issues)
        addErrorEvents(sorted, issues)

        val ordered = items.sortedWith(compareBy<VoiceTimelineItem> { it.order }.thenBy { it.startUptimeMs })
        val graphNodes = graphNodes(sorted)
        val graphEdges = graphNodes.zipWithNext { from, to ->
            TraceGraphEdge(
                fromEventName = from.eventName,
                toEventName = to.eventName,
                durationMs = to.timestampUptimeMs - from.timestampUptimeMs
            )
        }
        val performanceDurationMs = ordered.filter { it.includeInDuration }.sumOf { it.durationMs ?: 0L }
        return AnalyzedVoiceRequest(
            requestId = requestId,
            rawEvents = sorted,
            timelineItems = ordered,
            graphNodes = graphNodes,
            graphEdges = graphEdges,
            performanceDurationMs = performanceDurationMs,
            issues = issues
        )
    }

    private fun stageMatches(events: List<VoiceTraceEvent>): List<StageMatch> =
        profile.stageRules.mapNotNull { stage ->
            val begin = events.firstOrNull { it.name == stage.begin } ?: return@mapNotNull null
            val end = events.firstOrNull { it.name == stage.end && it.timestampUptimeMs >= begin.timestampUptimeMs }
                ?: return@mapNotNull null
            StageMatch(
                stage = stage,
                begin = begin,
                end = end
            )
        }

    private fun graphNodes(events: List<VoiceTraceEvent>): List<TraceGraphNode> {
        val seenNames = mutableSetOf<String>()
        return events
            .filter { event -> seenNames.add(event.name) }
            .filter { event ->
                val stage = stageForEvent(event.name)
                val marker = profile.markerFor(event.name)
                stage?.showInConversation == true || marker?.showInConversation == true || event.type == TraceEventType.ERROR
            }
            .map { event ->
                val stage = stageForEvent(event.name)
                val marker = profile.markerFor(event.name)
                TraceGraphNode(
                    eventName = event.name,
                    label = eventLabel(event.name, stage, marker),
                    timestampUptimeMs = event.timestampUptimeMs,
                    type = event.type,
                    category = stage?.category ?: marker?.category ?: TraceCategory.CUSTOM,
                    ruleId = stage?.id ?: marker?.name,
                    attributes = event.attributes
                )
            }
    }

    private fun stageForEvent(name: String): StageRule? =
        profile.stageRules.firstOrNull { it.begin == name || it.end == name }

    private fun eventLabel(name: String, stage: StageRule?, marker: MarkerRule?): String {
        if (marker != null) return marker.label
        if (stage == null) return name
        return when (name) {
            stage.begin -> "${stage.label}开始"
            stage.end -> "${stage.label}结束"
            else -> stage.label
        }
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
        stageMatches: List<StageMatch>,
        items: MutableList<VoiceTimelineItem>,
        issues: MutableList<TraceIssue>
    ) {
        for (stage in profile.stageRules) {
            val match = stageMatches.firstOrNull { it.stage.id == stage.id }
            if (match == null) {
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

            val begin = match.begin
            val end = match.end
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
