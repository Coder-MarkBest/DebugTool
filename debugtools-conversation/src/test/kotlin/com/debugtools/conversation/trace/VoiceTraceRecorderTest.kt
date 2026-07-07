package com.debugtools.conversation.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceRecorderTest {
    private val profile = voiceTraceProfile {
        requestBoundary {
            startEvents = listOf("AsrBegin")
            exitEvents = listOf("RequestExit")
            fallbackTimeoutMs = 1_000
        }
    }

    @Test
    fun `exit with requestId closes that request`() {
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(event("req1", "AsrBegin", TraceEventType.BEGIN, 100))
        recorder.record(event("req1", "RequestExit", TraceEventType.INSTANT, 200))

        val snapshot = recorder.snapshot()

        assertEquals(emptySet<String>(), snapshot.activeRequestIds)
        assertEquals(setOf("req1"), snapshot.closedRequestIds)
        assertTrue(snapshot.issues.isEmpty())
    }

    @Test
    fun `exit without requestId closes only active request`() {
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(event("req1", "AsrBegin", TraceEventType.BEGIN, 100))
        recorder.record(event(null, "RequestExit", TraceEventType.INSTANT, 200))

        val snapshot = recorder.snapshot()

        assertEquals(emptySet<String>(), snapshot.activeRequestIds)
        assertEquals(setOf("req1"), snapshot.closedRequestIds)
    }

    @Test
    fun `exit without requestId among multiple active requests warns`() {
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(event("req1", "AsrBegin", TraceEventType.BEGIN, 100))
        recorder.record(event("req2", "AsrBegin", TraceEventType.BEGIN, 150))
        recorder.record(event(null, "RequestExit", TraceEventType.INSTANT, 200))

        val snapshot = recorder.snapshot()

        assertEquals(setOf("req1"), snapshot.activeRequestIds)
        assertEquals(setOf("req2"), snapshot.closedRequestIds)
        assertTrue(snapshot.issues.any { it.type == "EXIT_MATCHED_BY_LATEST_ACTIVE" })
    }

    private fun event(requestId: String?, name: String, type: TraceEventType, time: Long) =
        VoiceTraceEvent(requestId, name, type, timestampUptimeMs = time, wallTimeMs = time)
}
