package com.debugtools.startup

import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.recording.RecordingIssueSeverity
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupMonitorModuleOverviewTest {
    @Test fun `overview is unknown when no startup data exists`() {
        val item = StartupMonitorModule.overviewItem(null)

        assertEquals("startup", item.moduleId)
        assertEquals("启动链路", item.title)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
        assertEquals("暂无启动数据", item.primaryText)
    }

    @Test fun `overview reports failed startup as error`() {
        val item = StartupMonitorModule.overviewItem(
            session(listOf(step("asr", status = StepStatus.FAILED, error = "load failed")))
        )

        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("1失败"))
    }

    @Test fun `overview reports slow and never ended startup as warning`() {
        val item = StartupMonitorModule.overviewItem(
            session(
                listOf(
                    step("config", start = 0, end = 80),
                    step("nlu", start = 80, end = null, status = StepStatus.RUNNING)
                )
            )
        )

        assertEquals(OverviewStatus.WARNING, item.status)
        assertTrue(item.secondaryText.orEmpty().contains("慢步骤 1"))
        assertTrue(item.secondaryText.orEmpty().contains("未结束 1"))
    }

    @Test fun `recording issues expose failed initialization step`() {
        val issues = StartupMonitorModule.recordingIssues(
            listOf(session(listOf(step("nlu", status = StepStatus.FAILED, error = "engine unavailable"))))
        )

        val issue = issues.single()
        assertEquals(RecordingIssueSeverity.CRITICAL, issue.severity)
        assertEquals("ERROR", issue.type)
        assertTrue(issue.detail.contains("nlu"))
        assertTrue(issue.evidence.contains("engine unavailable"))
        assertTrue(issue.suggestion.contains("initialization"))
    }

    private fun session(steps: List<StartupStep>) = StartupSession(
        sessionId = "startup-1",
        startedAtWallMs = 1000L,
        launchUptimeMs = 0L,
        appVersion = "1.0",
        steps = steps,
        completedUptimeMs = 200L,
        completedExplicitly = true
    )

    private fun step(
        name: String,
        start: Long = 0L,
        end: Long? = 20L,
        status: StepStatus = StepStatus.SUCCESS,
        error: String? = null
    ) = StartupStep(name, emptyList(), start, end, status, error, "test")
}
