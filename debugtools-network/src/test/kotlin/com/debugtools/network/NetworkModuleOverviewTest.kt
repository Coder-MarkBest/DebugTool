package com.debugtools.network

import com.debugtools.core.overview.OverviewStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkModuleOverviewTest {
    @Test fun `overview reports checking as unknown`() {
        val item = NetworkModule.overviewItem("检测中...")

        assertEquals("debugtools_network", item.moduleId)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
    }

    @Test fun `overview reports offline as error`() {
        val item = NetworkModule.overviewItem("离线")

        assertEquals(OverviewStatus.ERROR, item.status)
    }
}
