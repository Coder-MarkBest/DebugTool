package com.debugtools.conversation.recording

import com.debugtools.conversation.trace.AnalyzedVoiceRequest
import com.debugtools.conversation.trace.TraceIssue
import com.debugtools.conversation.trace.TraceIssueSeverity
import com.debugtools.conversation.trace.VoiceTraceAnalyzer
import com.debugtools.conversation.trace.VoiceTraceEvent
import com.debugtools.conversation.trace.VoiceTraceProfile
import com.debugtools.conversation.trace.VoiceTraceRecorder
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.recording.RecordingIssue
import com.debugtools.core.recording.RecordingIssueSeverity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ConversationRecordingExporter(
    private val profile: VoiceTraceProfile,
    private val recorder: VoiceTraceRecorder
) {
    fun export(context: RecordingContext): ModuleRecordingResult {
        val dir = File(context.rootDir, "conversation").apply { mkdirs() }
        val snapshot = recorder.snapshot()
        val rawFile = File(dir, "raw-events.json")
        val requestsFile = File(dir, "requests.json")
        val analyzer = VoiceTraceAnalyzer(profile)
        val analyzed = snapshot.eventsByRequest.map { (id, events) -> analyzer.analyze(id, events) }

        rawFile.writeText(JSONObject().apply {
            put("orphanEvents", JSONArray(snapshot.orphanEvents.map { it.toJson() }))
            put("requests", JSONObject().apply {
                snapshot.eventsByRequest.forEach { (id, events) ->
                    put(id, JSONArray(events.map { it.toJson() }))
                }
            })
        }.toString(2))

        requestsFile.writeText(JSONArray(analyzed.map { it.toJson() }).toString(2))

        return ModuleRecordingResult(
            moduleId = "conversation",
            files = listOf(rawFile, requestsFile),
            issues = analyzed.flatMap { it.toRecordingIssues() },
            summary = mapOf("requests" to snapshot.eventsByRequest.size.toString())
        )
    }
}

private fun VoiceTraceEvent.toJson() = JSONObject().apply {
    put("requestId", requestId ?: JSONObject.NULL)
    put("name", name)
    put("type", type.name)
    put("timestampUptimeMs", timestampUptimeMs)
    put("wallTimeMs", wallTimeMs)
    put("attributes", JSONObject(attributes))
}

private fun AnalyzedVoiceRequest.toJson() = JSONObject().apply {
    put("requestId", requestId)
    put("performanceDurationMs", performanceDurationMs)
    put("timelineItems", JSONArray(timelineItems.map { item ->
        JSONObject().apply {
            put("id", item.id)
            put("label", item.label)
            put("sourceName", item.sourceName)
            put("startUptimeMs", item.startUptimeMs)
            put("endUptimeMs", item.endUptimeMs ?: JSONObject.NULL)
            put("durationMs", item.durationMs ?: JSONObject.NULL)
            put("includeInDuration", item.includeInDuration)
            put("category", item.category.name)
        }
    }))
    put("graphNodes", JSONArray(graphNodes.map { node ->
        JSONObject().apply {
            put("eventName", node.eventName)
            put("label", node.label)
            put("timestampUptimeMs", node.timestampUptimeMs)
            put("type", node.type.name)
            put("category", node.category.name)
            put("ruleId", node.ruleId ?: JSONObject.NULL)
            put("attributes", JSONObject(node.attributes))
        }
    }))
    put("graphEdges", JSONArray(graphEdges.map { edge ->
        JSONObject().apply {
            put("fromEventName", edge.fromEventName)
            put("toEventName", edge.toEventName)
            put("durationMs", edge.durationMs)
        }
    }))
    put("issues", JSONArray(issues.map {
        JSONObject().apply {
            put("severity", it.severity.name)
            put("type", it.type)
            put("detail", it.detail)
            put("stageId", it.stageId ?: JSONObject.NULL)
        }
    }))
}

private fun AnalyzedVoiceRequest.toRecordingIssues(): List<RecordingIssue> =
    issues.map { issue ->
        RecordingIssue(
            severity = issue.toRecordingSeverity(),
            type = issue.type,
            detail = issue.detail,
            moduleId = "conversation",
            evidence = buildString {
                append("requestId=").append(requestId)
                issue.stageId?.let { append(", stageId=").append(it) }
                append(", events=").append(rawEvents.size)
            },
            suggestion = issue.suggestion()
        )
    }

private fun TraceIssue.toRecordingSeverity(): RecordingIssueSeverity = when (severity) {
    TraceIssueSeverity.CRITICAL -> RecordingIssueSeverity.CRITICAL
    TraceIssueSeverity.WARNING -> RecordingIssueSeverity.WARNING
    TraceIssueSeverity.INFO -> RecordingIssueSeverity.INFO
}

private fun TraceIssue.suggestion(): String = when (type) {
    "REQUIRED_STAGE_MISSING" -> "Check whether the required begin/end events were emitted for this request."
    "SLOW_STAGE" -> "Inspect the stage owner and adjacent network or CPU records during this request."
    "ERROR_EVENT" -> "Open raw-events.json and check the error event attributes from the host app."
    else -> "Use requests.json and raw-events.json to inspect the affected request."
}
