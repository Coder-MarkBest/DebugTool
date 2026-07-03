package com.debugtools.conversation.recording

import com.debugtools.conversation.trace.AnalyzedVoiceRequest
import com.debugtools.conversation.trace.VoiceTraceAnalyzer
import com.debugtools.conversation.trace.VoiceTraceEvent
import com.debugtools.conversation.trace.VoiceTraceProfile
import com.debugtools.conversation.trace.VoiceTraceRecorder
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.RecordingContext
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

        rawFile.writeText(JSONObject().apply {
            put("orphanEvents", JSONArray(snapshot.orphanEvents.map { it.toJson() }))
            put("requests", JSONObject().apply {
                snapshot.eventsByRequest.forEach { (id, events) ->
                    put(id, JSONArray(events.map { it.toJson() }))
                }
            })
        }.toString(2))

        requestsFile.writeText(JSONArray(snapshot.eventsByRequest.map { (id, events) ->
            analyzer.analyze(id, events).toJson()
        }).toString(2))

        return ModuleRecordingResult(
            moduleId = "conversation",
            files = listOf(rawFile, requestsFile),
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
    put("issues", JSONArray(issues.map {
        JSONObject().apply {
            put("severity", it.severity.name)
            put("type", it.type)
            put("detail", it.detail)
            put("stageId", it.stageId ?: JSONObject.NULL)
        }
    }))
}
