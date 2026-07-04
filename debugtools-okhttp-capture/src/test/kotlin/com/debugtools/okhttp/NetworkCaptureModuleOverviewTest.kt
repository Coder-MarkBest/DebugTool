package com.debugtools.okhttp

import com.debugtools.core.overview.OverviewStatus
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.repository.NetworkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun record(responseCode: Int) = HttpRecord(
        id = "r1",
        timestamp = 1L,
        method = "GET",
        url = "https://example.test",
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
