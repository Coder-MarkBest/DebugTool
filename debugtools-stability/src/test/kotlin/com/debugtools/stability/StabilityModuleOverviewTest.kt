package com.debugtools.stability

import com.debugtools.core.overview.OverviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilityModuleOverviewTest {
    @Test fun `overview is unknown without status or crashes`() {
        val item = StabilityModule.overviewItem(emptyMap(), crashCount = 0)

        assertEquals("stability", item.moduleId)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
    }

    @Test fun `overview reports crash as error`() {
        val item = StabilityModule.overviewItem(mapOf("com.voice" to true), crashCount = 1)

        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("Crash 1"))
    }
}
