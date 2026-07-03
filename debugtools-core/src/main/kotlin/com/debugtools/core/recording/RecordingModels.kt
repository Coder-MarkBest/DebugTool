package com.debugtools.core.recording

import java.io.File

enum class RecordingIssueSeverity { CRITICAL, WARNING, INFO }

data class RecordingIssue(
    val severity: RecordingIssueSeverity,
    val type: String,
    val detail: String,
    val moduleId: String? = null
)

data class RecordingContext(
    val recordingId: String,
    val startedAtWallMs: Long,
    val startedAtUptimeMs: Long,
    val rootDir: File
)

data class ModuleRecordingSnapshot(
    val moduleId: String,
    val files: List<File> = emptyList(),
    val summary: Map<String, String> = emptyMap()
)

data class ModuleRecordingResult(
    val moduleId: String,
    val files: List<File> = emptyList(),
    val issues: List<RecordingIssue> = emptyList(),
    val summary: Map<String, String> = emptyMap()
)

data class RecordingSessionReport(
    val context: RecordingContext,
    val endedAtWallMs: Long,
    val rootDir: File,
    val moduleResults: List<ModuleRecordingResult>,
    val issues: List<RecordingIssue>
)

sealed class RecordingState {
    object Idle : RecordingState()
    data class Active(val context: RecordingContext) : RecordingState()
}
