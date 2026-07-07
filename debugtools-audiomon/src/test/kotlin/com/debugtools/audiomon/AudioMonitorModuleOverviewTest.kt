package com.debugtools.audiomon

import com.debugtools.core.overview.OverviewStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMonitorModuleOverviewTest {
    @Test fun `overview reports active monitoring as recording`() {
        val item = AudioMonitorModule.overviewItem(monitoring = true, hasRecordPermission = true)

        assertEquals("audiomon", item.moduleId)
        assertEquals(OverviewStatus.RECORDING, item.status)
        assertEquals("录制中", item.primaryText)
    }

    @Test fun `overview reports missing permission as warning`() {
        val item = AudioMonitorModule.overviewItem(monitoring = false, hasRecordPermission = false)

        assertEquals(OverviewStatus.WARNING, item.status)
    }
}
