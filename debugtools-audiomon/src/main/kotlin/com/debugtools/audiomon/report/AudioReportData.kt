package com.debugtools.audiomon.report

import java.io.File

/**
 * Artifacts of one completed recording session handed to [AudioReporter].
 *
 * Stream A files are null when the host never pushed processed audio during
 * the session. [metadata] is the session.json describing the whole session.
 */
data class AudioReportData(
    val sessionId: String,
    val sessionDir: File,
    val streamBWav: File?,
    val streamBFeatures: File?,
    val streamAWav: File?,
    val streamAFeatures: File?,
    val metadata: File
)
