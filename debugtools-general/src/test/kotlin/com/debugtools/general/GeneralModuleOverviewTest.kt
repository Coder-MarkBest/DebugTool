package com.debugtools.general

import com.debugtools.core.overview.OverviewStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GeneralModuleOverviewTest {
    @Suppress("DEPRECATION")
    @Test fun `general module alias uses availability overview`() {
        val item = GeneralModule.overviewItem(emptyList())

        assertEquals("debugtools_availability", item.moduleId)
        assertEquals("可用性", item.title)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
    }
}
