package com.debugtools.conversation.protocol

/** A problem the analyzer found in a turn. */
data class TurnIssue(
    val type: TurnIssueType,
    val stageName: String?,
    val detail: String,
    val severity: TurnIssueSeverity
)

enum class TurnIssueType { STAGE_FAILED, SLOW_STAGE, TURN_TIMEOUT, TURN_ABORTED, PIPELINE_GAP }
enum class TurnIssueSeverity { ERROR, WARN, INFO }
