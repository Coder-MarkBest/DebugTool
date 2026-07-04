package com.debugtools.perfmon

import com.debugtools.core.overview.OverviewStatus
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.repository.PerfRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfMonitorModuleOverviewTest {
    @Test fun `overview is unknown without samples`() {
        val item = PerfMonitorModule.overviewItem(PerfRepository(Config()).state.value, Config())

        assertEquals("debugtools_perfmon", item.moduleId)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
    }

    @Test fun `overview reports red cpu as error`() {
        val config = Config(cpuRedThreshold = 80)
        val repo = PerfRepository(config)
        repo.addSample(sample(cpuPercent = 95f))

        val item = PerfMonitorModule.overviewItem(repo.state.value, config)

        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("CPU max 95%"))
    }

    private fun sample(cpuPercent: Float) = ProcessSample(
        target = ProcessTarget.ByName("com.voice"),
        pid = 123,
        timestamp = 1L,
        cpuPercent = cpuPercent,
        rssBytes = 1024L,
        threadCount = 4,
        alive = true
    )
}
