package com.debugtools.startup.protocol

/** A problem the analyzer found in a session. */
data class StartupIssue(
    val type: IssueType,
    val stepName: String?,
    val detail: String,
    val severity: Severity
)

enum class IssueType { ERROR, SLOW, DEP_VIOLATION, NEVER_ENDED, DEP_CYCLE, PARALLELIZABLE }
enum class Severity { ERROR, WARN, INFO }
