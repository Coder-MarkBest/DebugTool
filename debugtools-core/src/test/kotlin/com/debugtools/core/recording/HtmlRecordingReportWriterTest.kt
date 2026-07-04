package com.debugtools.core.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HtmlRecordingReportWriterTest {
    @Test
    fun `writer includes sections and issue severities`() {
        val dir = Files.createTempDirectory("debugtools-report").toFile()
        val context = RecordingContext("rec1", 1000, 10, dir)
        val report = RecordingSessionReport(
            context = context,
            endedAtWallMs = 2000,
            rootDir = dir,
            moduleResults = listOf(ModuleRecordingResult("startup", summary = mapOf("failed" to "1"))),
            issues = listOf(RecordingIssue(RecordingIssueSeverity.CRITICAL, "STEP_FAILED", "asr failed", "startup"))
        )

        val file = HtmlRecordingReportWriter().write(report, File(dir, "report.html"))
        val html = file.readText()

        assertTrue(html.contains("DebugTools Recording Report"))
        assertTrue(html.contains("Voice Requests"))
        assertTrue(html.contains("Startup"))
        assertTrue(html.contains("critical"))
        assertTrue(html.contains("asr failed"))
    }
}
