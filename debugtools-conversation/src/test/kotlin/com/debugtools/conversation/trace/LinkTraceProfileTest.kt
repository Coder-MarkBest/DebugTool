package com.debugtools.conversation.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTraceProfileTest {
    @Test
    fun `link trace dsl uses business neutral names`() {
        val profile = linkTraceProfile {
            traceIdKey = "orderId"
            requestBoundary {
                startEvents = listOf("OrderBegin")
                exitEvents = listOf("OrderFinished")
            }
            stage("pay") {
                begin = "PayBegin"
                end = "PayEnd"
                label = "Payment"
                category = TraceCategory.CUSTOM
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 1_000
                required = true
                order = 10
            }
        }

        assertEquals("orderId", profile.requestKey)
        assertEquals("pay", profile.stageRules.single().id)
        assertTrue(profile.stageRules.single().showInConversation)
    }

    @Test
    fun `voice assistant standard profile maps full built in assistant chain`() {
        val profile = VoiceAssistantTraceProfiles.standard(
            VoiceAssistantTraceMapping(
                exit = "dialogExit",
                vadBegin = "VadBegin",
                vadEnd = "VadEnd",
                asrBegin = "AsrBegin",
                asrEnd = "AsrEnd",
                asrArbitrationBegin = "AsrArbitrationBegin",
                asrArbitrationEnd = "AsrArbitrationEnd",
                nluBegin = "NluBegin",
                nluEnd = "NluEnd",
                nluArbitrationBegin = "NluArbitrationBegin",
                nluArbitrationEnd = "NluArbitrationEnd",
                executionEngineBegin = "ExecutionEngineBegin",
                executionEngineEnd = "ExecutionEngineEnd",
                ttsTextReceivedBegin = "TtsTextReceivedBegin",
                ttsTextReceivedEnd = "TtsTextReceivedEnd",
                audioFocusBegin = "AudioFocusBegin",
                audioFocusEnd = "AudioFocusEnd",
                cacheReadBegin = "CacheReadBegin",
                cacheReadEnd = "CacheReadEnd",
                synthesisBegin = "SynthesisBegin",
                synthesisEnd = "SynthesisEnd",
                audioTrackWriteBegin = "AudioTrackWriteBegin",
                audioTrackWriteEnd = "AudioTrackWriteEnd",
                ttsBegin = "TtsBegin",
                ttsEnd = "TtsEnd"
            )
        )

        assertEquals("requestId", profile.requestKey)
        assertEquals(listOf("dialogExit"), profile.boundary.exitEvents)
        assertEquals(
            listOf(
                "VAD",
                "ASR",
                "ASR_ARBITRATION",
                "NLU",
                "NLU_ARBITRATION",
                "EXECUTION_ENGINE",
                "TTS_TEXT_RECEIVED",
                "AUDIO_FOCUS",
                "CACHE_READ",
                "TTS_SYNTHESIS",
                "AUDIO_TRACK_WRITE",
                "TTS"
            ),
            profile.stageRules.map { it.id }
        )
        assertTrue(profile.stageRules.first { it.id == "ASR" }.required)
        assertEquals("ASR仲裁", profile.stageRules.first { it.id == "ASR_ARBITRATION" }.label)
        assertEquals(TraceCategory.NLU, profile.stageRules.first { it.id == "NLU" }.category)
        assertEquals(TraceCategory.TTS, profile.stageRules.first { it.id == "AUDIO_TRACK_WRITE" }.category)
    }
}
