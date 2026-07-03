package com.debugtools.conversation.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceProfileTest {
    @Test
    fun `dsl builds stage marker and boundary rules`() {
        val profile = voiceTraceProfile {
            requestKey = "requestId"
            requestBoundary {
                startEvents = listOf("vadBegin", "AsrBegin")
                exitEvents = listOf("DialogExit")
                fallbackTimeoutMs = 30_000
            }
            stage("ASR") {
                begin = "AsrBegin"
                end = "AsrEnd"
                label = "ASR"
                category = TraceCategory.ASR
                showInConversation = true
                includeInDuration = true
                warnIfSlowMs = 800
                required = true
                order = 20
            }
            marker("internalCacheHit") {
                showInConversation = false
                includeInDuration = false
            }
        }

        assertEquals("requestId", profile.requestKey)
        assertEquals(listOf("vadBegin", "AsrBegin"), profile.boundary.startEvents)
        assertEquals(listOf("DialogExit"), profile.boundary.exitEvents)
        assertEquals(30_000L, profile.boundary.fallbackTimeoutMs)

        val asr = profile.stageRules.single()
        assertEquals("ASR", asr.id)
        assertEquals("AsrBegin", asr.begin)
        assertEquals("AsrEnd", asr.end)
        assertEquals(TraceCategory.ASR, asr.category)
        assertTrue(asr.showInConversation)
        assertTrue(asr.includeInDuration)
        assertTrue(asr.required)
        assertEquals(20, asr.order)

        val marker = profile.markerRules.single()
        assertEquals("internalCacheHit", marker.name)
        assertFalse(marker.showInConversation)
        assertFalse(marker.includeInDuration)
    }
}
