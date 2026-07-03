package com.debugtools.conversation.trace

enum class TraceEventType { BEGIN, END, INSTANT, ERROR }

enum class TraceOutcome { SUCCESS, FAILED, TIMEOUT, ABORTED }

enum class TraceCategory { VAD, ASR, NLU, DM, TTS, TOOL, NETWORK, CUSTOM }

enum class TraceIssueSeverity { CRITICAL, WARNING, INFO }

data class VoiceTraceEvent(
    val requestId: String?,
    val name: String,
    val type: TraceEventType,
    val timestampUptimeMs: Long,
    val wallTimeMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, String> = emptyMap()
)

data class TraceIssue(
    val severity: TraceIssueSeverity,
    val type: String,
    val detail: String,
    val stageId: String? = null
)
