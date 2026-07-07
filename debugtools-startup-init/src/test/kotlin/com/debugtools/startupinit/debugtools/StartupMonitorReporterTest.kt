package com.debugtools.startupinit.debugtools

import com.debugtools.startup.AppStartupMonitor
import com.debugtools.startupinit.StartupInitFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class StartupMonitorReporterTest {
    @Test
    fun `bridge records task events into AppStartupMonitor`() = runTest {
        AppStartupMonitor.begin("bridge_test_reset")
        AppStartupMonitor.success("bridge_test_reset")

        val result = StartupInitFlow.builder()
            .task("config") { runBlockingInit { } }
            .task("asr", dependsOn = listOf("config")) { runBlockingInit { } }
            .reportToStartupMonitor()
            .run()

        val session = AppStartupMonitor.currentSession()
        assertTrue(result.success)
        assertEquals(listOf("config", "asr"), session!!.steps.takeLast(2).map { it.name })
        assertTrue(session.steps.first { it.name == "asr" }.dependsOn.contains("config"))
    }

    @Test
    fun `bridge records skipped task as synthetic failed step`() = runTest {
        val result = StartupInitFlow.builder()
            .task("config") { runBlockingInit { error("boom") } }
            .task("asr", dependsOn = listOf("config")) { runBlockingInit { } }
            .reportToStartupMonitor()
            .run()

        val session = AppStartupMonitor.currentSession()
        assertTrue(!result.success)
        assertTrue(session!!.steps.any { it.name == "init_flow_skipped:asr" && it.error!!.contains("config") })
    }
}
