package com.debugtools.startupinit.debugtools

import android.content.Context
import com.debugtools.startup.AppStartupMonitor
import com.debugtools.startup.protocol.StepStatus
import com.debugtools.startupinit.StartupInitFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StartupMonitorReporterTest {
    @Before
    fun setUp() {
        resetAppStartupMonitor()
        AppStartupMonitor.init(ApplicationProvider.getApplicationContext(), appVersion = "test")
    }

    @Test
    fun `bridge records task events into AppStartupMonitor`() = runTest {
        val result = StartupInitFlow.builder()
            .task("config") { runBlockingInit { } }
            .task("asr", dependsOn = listOf("config")) { runBlockingInit { } }
            .reportToStartupMonitor()
            .run()

        val session = AppStartupMonitor.currentSession()!!
        val config = session.steps.first { it.name == "config" }
        val asr = session.steps.first { it.name == "asr" }

        assertTrue(result.success)
        assertEquals(listOf("config", "asr"), session.steps.takeLast(2).map { it.name })
        assertEquals(StepStatus.SUCCESS, config.status)
        assertEquals(StepStatus.SUCCESS, asr.status)
        assertTrue(asr.dependsOn.contains("config"))
        assertTrue(session.completedUptimeMs != null)
        assertTrue(session.completedExplicitly)
    }

    @Test
    fun `bridge records skipped task as synthetic failed step`() = runTest {
        val result = StartupInitFlow.builder()
            .task("config") { runBlockingInit { error("boom") } }
            .task("asr", dependsOn = listOf("config")) { runBlockingInit { } }
            .reportToStartupMonitor()
            .run()

        val session = AppStartupMonitor.currentSession()!!
        val skipped = session.steps.first { it.name == "init_flow_skipped:asr" }

        assertTrue(!result.success)
        assertEquals(StepStatus.FAILED, skipped.status)
        assertTrue(skipped.error!!.contains("config"))
    }

    private fun resetAppStartupMonitor() {
        val monitorClass = AppStartupMonitor::class.java
        monitorClass.getDeclaredField("recorder").apply {
            isAccessible = true
            set(AppStartupMonitor, null)
        }
        monitorClass.getDeclaredField("store").apply {
            isAccessible = true
            set(AppStartupMonitor, null)
        }
        monitorClass.getDeclaredField("persisted").apply {
            isAccessible = true
            setBoolean(AppStartupMonitor, false)
        }
    }

    private object ApplicationProvider {
        fun getApplicationContext(): Context = RuntimeEnvironment.getApplication()
    }
}
