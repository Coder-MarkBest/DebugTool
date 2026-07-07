package com.debugtools.conversation.trace

data class VoiceAssistantTraceMapping(
    val requestKey: String = "requestId",
    val startEvents: List<String> = listOf("VadBegin"),
    val exit: String = "DialogExit",
    val vadBegin: String = "VadBegin",
    val vadEnd: String = "VadEnd",
    val asrBegin: String = "AsrBegin",
    val asrEnd: String = "AsrEnd",
    val asrArbitrationBegin: String = "AsrArbitrationBegin",
    val asrArbitrationEnd: String = "AsrArbitrationEnd",
    val nluBegin: String = "NluBegin",
    val nluEnd: String = "NluEnd",
    val nluArbitrationBegin: String = "NluArbitrationBegin",
    val nluArbitrationEnd: String = "NluArbitrationEnd",
    val executionEngineBegin: String = "ExecutionEngineBegin",
    val executionEngineEnd: String = "ExecutionEngineEnd",
    val ttsTextReceivedBegin: String = "TtsTextReceivedBegin",
    val ttsTextReceivedEnd: String = "TtsTextReceivedEnd",
    val audioFocusBegin: String = "AudioFocusBegin",
    val audioFocusEnd: String = "AudioFocusEnd",
    val cacheReadBegin: String = "CacheReadBegin",
    val cacheReadEnd: String = "CacheReadEnd",
    val synthesisBegin: String = "SynthesisBegin",
    val synthesisEnd: String = "SynthesisEnd",
    val audioTrackWriteBegin: String = "AudioTrackWriteBegin",
    val audioTrackWriteEnd: String = "AudioTrackWriteEnd",
    val ttsBegin: String = "TtsBegin",
    val ttsEnd: String = "TtsEnd"
)

object VoiceAssistantTraceProfiles {
    fun standard(
        mapping: VoiceAssistantTraceMapping = VoiceAssistantTraceMapping(),
        extraMarkers: List<MarkerRule> = emptyList()
    ): LinkTraceProfile {
        val profile = linkTraceProfile {
            requestKey = mapping.requestKey
            requestBoundary {
                startEvents = mapping.startEvents
                exitEvents = listOf(mapping.exit)
                fallbackTimeoutMs = 30_000
            }
            stage("VAD") {
                begin = mapping.vadBegin
                end = mapping.vadEnd
                label = "VAD"
                category = TraceCategory.VAD
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 300
                required = true
                order = 10
            }
            stage("ASR") {
                begin = mapping.asrBegin
                end = mapping.asrEnd
                label = "ASR"
                category = TraceCategory.ASR
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 800
                required = true
                order = 20
            }
            stage("ASR_ARBITRATION") {
                begin = mapping.asrArbitrationBegin
                end = mapping.asrArbitrationEnd
                label = "ASR仲裁"
                category = TraceCategory.ASR
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 300
                required = false
                order = 30
            }
            stage("NLU") {
                begin = mapping.nluBegin
                end = mapping.nluEnd
                label = "NLU"
                category = TraceCategory.NLU
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 500
                required = true
                order = 40
            }
            stage("NLU_ARBITRATION") {
                begin = mapping.nluArbitrationBegin
                end = mapping.nluArbitrationEnd
                label = "NLU仲裁"
                category = TraceCategory.NLU
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 300
                required = false
                order = 50
            }
            stage("EXECUTION_ENGINE") {
                begin = mapping.executionEngineBegin
                end = mapping.executionEngineEnd
                label = "执行引擎"
                category = TraceCategory.TOOL
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 1_500
                required = false
                order = 60
            }
            stage("TTS_TEXT_RECEIVED") {
                begin = mapping.ttsTextReceivedBegin
                end = mapping.ttsTextReceivedEnd
                label = "TTS收到文本"
                category = TraceCategory.TTS
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 200
                required = false
                order = 70
            }
            stage("AUDIO_FOCUS") {
                begin = mapping.audioFocusBegin
                end = mapping.audioFocusEnd
                label = "申请焦点"
                category = TraceCategory.TTS
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 300
                required = false
                order = 80
            }
            stage("CACHE_READ") {
                begin = mapping.cacheReadBegin
                end = mapping.cacheReadEnd
                label = "读取缓存"
                category = TraceCategory.TTS
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 200
                required = false
                order = 90
            }
            stage("TTS_SYNTHESIS") {
                begin = mapping.synthesisBegin
                end = mapping.synthesisEnd
                label = "TTS合成"
                category = TraceCategory.TTS
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 1_000
                required = false
                order = 100
            }
            stage("AUDIO_TRACK_WRITE") {
                begin = mapping.audioTrackWriteBegin
                end = mapping.audioTrackWriteEnd
                label = "写入AudioTrack"
                category = TraceCategory.TTS
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 300
                required = false
                order = 110
            }
            stage("TTS") {
                begin = mapping.ttsBegin
                end = mapping.ttsEnd
                label = "TTS"
                category = TraceCategory.TTS
                showInTimeline = true
                includeInDuration = true
                warnIfSlowMs = 1_000
                required = false
                order = 120
            }
        }
        return if (extraMarkers.isEmpty()) profile else profile.copy(markerRules = profile.markerRules + extraMarkers)
    }
}
