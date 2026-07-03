package com.debugtools.conversation.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceAnalyzerTest {
    private val profile = voiceTraceProfile {
        requestBoundary { exitEvents = listOf("RequestExit") }
        marker("vadEnd") {
            label = "VAD End"
            showInConversation = true
            includeInDuration = false
            order = 10
        }
        marker("hidden") {
            showInConversation = false
            includeInDuration = false
            order = 11
        }
        stage("ASR") {
            begin = "AsrBegin"
            end = "AsrEnd"
            label = "ASR"
            category = TraceCategory.ASR
            showInConversation = true
            includeInDuration = true
            warnIfSlowMs = 100
            required = true
            order = 20
        }
    }

    @Test
    fun `analyzer pairs begin end and keeps hidden raw events`() {
        val events = listOf(
            event("req1", "AsrBegin", TraceEventType.BEGIN, 100),
            event("req1", "hidden", TraceEventType.INSTANT, 120),
            event("req1", "vadEnd", TraceEventType.INSTANT, 130),
            event("req1", "AsrEnd", TraceEventType.END, 260)
        )

        val result = VoiceTraceAnalyzer(profile).analyze("req1", events)

        assertEquals(4, result.rawEvents.size)
        assertEquals(listOf("VAD End", "ASR"), result.timelineItems.map { it.label })
        assertEquals(160L, result.performanceDurationMs)
        assertTrue(result.issues.any { it.type == "SLOW_STAGE" && it.stageId == "ASR" })
        assertTrue(result.timelineItems.none { it.sourceName == "hidden" })
    }

    @Test
    fun `missing required stage is critical`() {
        val result = VoiceTraceAnalyzer(profile).analyze("req1", listOf(event("req1", "vadEnd", TraceEventType.INSTANT, 100)))

        assertTrue(result.issues.any {
            it.severity == TraceIssueSeverity.CRITICAL && it.type == "REQUIRED_STAGE_MISSING" && it.stageId == "ASR"
        })
    }

    private fun event(requestId: String, name: String, type: TraceEventType, time: Long) =
        VoiceTraceEvent(requestId, name, type, timestampUptimeMs = time, wallTimeMs = time)
}
