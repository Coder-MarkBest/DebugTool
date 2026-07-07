package com.debugtools.network

import com.debugtools.core.overview.OverviewStatus
import com.debugtools.okhttp.NetworkCaptureModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModuleFusionTest {
    @Test fun `builder keeps a single visible network tab when capture is hosted`() {
        val capture = NetworkCaptureModule.create()
        val module = NetworkModule.builder()
            .gateway("8.8.8.8")
            .capture(capture)
            .build()

        assertEquals("debugtools_network", module.moduleId)
        assertEquals("网络", module.tabTitle)
        assertTrue(module.hasCaptureForTest())
    }

    @Test fun `overview merges quality state and capture errors`() {
        val item = NetworkModule.overviewItem(
            stateText = "WiFi 良好 · 20ms",
            captureSummary = NetworkCaptureModule.NetworkCaptureSummary(
                httpCount = 3,
                webSocketCount = 1,
                webSocketFrameCount = 5,
                errorCount = 2
            )
        )

        assertEquals("debugtools_network", item.moduleId)
        assertEquals("网络", item.title)
        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("WiFi 良好"))
        assertTrue(item.primaryText.contains("2错误"))
    }
}
