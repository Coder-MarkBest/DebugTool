package com.debugtools.perfmon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.debugtools.perfmon.data.ProcessTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerfMonitorModuleTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun `moduleId and tabTitle are set`() {
        val m = PerfMonitorModule.builder()
            .addProcessByName("com.example")
            .build()
        assertEquals("debugtools_perfmon", m.moduleId)
        assertEquals("性能监控", m.tabTitle)
    }

    @Test fun `builder stores targets`() {
        val m = PerfMonitorModule.builder()
            .addProcessByName("a")
            .addProcessByPid(123)
            .build()
        assertEquals(2, m.targetsForTest().size)
        assertTrue(m.targetsForTest().any { it is ProcessTarget.ByName && it.processName == "a" })
        assertTrue(m.targetsForTest().any { it is ProcessTarget.ByPid && it.pid == 123 })
    }

    @Test fun `builder clamps updateIntervalSec to range`() {
        val tooSmall = PerfMonitorModule.builder().updateIntervalSec(1).addProcessByPid(1).build()
        assertEquals(5, tooSmall.configForTest().updateIntervalSec)
        val tooBig = PerfMonitorModule.builder().updateIntervalSec(120).addProcessByPid(1).build()
        assertEquals(60, tooBig.configForTest().updateIntervalSec)
    }
}
