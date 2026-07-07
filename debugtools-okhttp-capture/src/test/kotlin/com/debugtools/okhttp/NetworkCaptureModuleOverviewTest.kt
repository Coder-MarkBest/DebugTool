package com.debugtools.okhttp

import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.recording.RecordingIssueSeverity
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.repository.NetworkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NetworkCaptureModuleOverviewTest {
    @Test fun `overview reports http errors`() {
        val repo = NetworkRepository(Config())
        repo.addHttp(record(responseCode = 500))

        val item = NetworkCaptureModule.overviewItem(repo.snapshot())

        assertEquals("debugtools_okhttp_capture", item.moduleId)
        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("1错误"))
    }

    @Test fun `capture summary reports counts for host modules`() {
        val module = NetworkCaptureModule.create()
        module.repositoryForTest().addHttp(record(responseCode = 500))

        val summary = module.captureSummary()

        assertEquals(1, summary.httpCount)
        assertEquals(0, summary.webSocketCount)
        assertEquals(0, summary.webSocketFrameCount)
        assertEquals(1, summary.errorCount)
    }

    @Test fun `recording exports top network failure as actionable issue`() {
        val module = NetworkCaptureModule.create()
        module.repositoryForTest().addHttp(record(responseCode = 503, url = "https://example.test/nlu?q=1"))
        val dir = Files.createTempDirectory("network-recording").toFile()

        val result = module.onRecordingStop(RecordingContext("r1", 1, 1, dir))

        val issue = result.issues.single()
        assertEquals(RecordingIssueSeverity.CRITICAL, issue.severity)
        assertEquals("HTTP_ERROR", issue.type)
        assertTrue(issue.detail.contains("GET /nlu"))
        assertTrue(issue.evidence.contains("HTTP 503"))
        assertTrue(issue.suggestion.contains("status"))
    }

    private fun record(responseCode: Int, url: String = "https://example.test") = HttpRecord(
        id = "r1",
        timestamp = 1L,
        method = "GET",
        url = url,
        protocol = "HTTP/1.1",
        requestHeaders = emptyList(),
        requestBody = null,
        requestBodyTruncated = false,
        responseCode = responseCode,
        responseHeaders = emptyList(),
        responseBody = null,
        responseBodyTruncated = false,
        durationMs = 10L,
        timing = null
    )
}
