package com.debugtools.audiomon.report

/**
 * Host-implemented network reporting hook for recorded audio sessions.
 *
 * The SDK invokes [report] on an IO thread once a session's artifacts are
 * written to disk. The host decides how to upload (OkHttp / proprietary
 * channel / not at all). Exceptions thrown here are caught by the SDK and
 * surfaced in the panel; they do not affect the recording pipeline.
 */
interface AudioReporter {
    fun report(session: AudioReportData)
}
