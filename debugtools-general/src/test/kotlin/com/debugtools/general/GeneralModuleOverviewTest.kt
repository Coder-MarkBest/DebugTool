package com.debugtools.general

import com.debugtools.core.overview.OverviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneralModuleOverviewTest {
    @Test fun `overview is unknown without data`() {
        val item = GeneralModule.overviewItem(emptyList(), emptyList())

        assertEquals("debugtools_general", item.moduleId)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
    }

    @Test fun `overview reports dead process as error`() {
        val item = GeneralModule.overviewItem(
            diskSizes = listOf("/data" to 1024L),
            processStates = listOf("com.voice" to false)
        )

        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("1异常"))
    }
}
