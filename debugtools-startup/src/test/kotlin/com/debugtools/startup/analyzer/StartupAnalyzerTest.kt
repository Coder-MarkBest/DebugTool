package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.IssueType
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAnalyzerTest {

    private fun step(
        name: String, deps: List<String> = emptyList(), start: Long = 0, end: Long? = 1,
        status: StepStatus = StepStatus.SUCCESS, error: String? = null
    ) = StartupStep(name, deps, start, end, status, error, "t")

    private fun session(steps: List<StartupStep>, completed: Long? = 1000L) =
        StartupSession("s", 0L, launchUptimeMs = 0L, appVersion = null,
            steps = steps, completedUptimeMs = completed, completedExplicitly = true)

    private fun types(s: StartupSession) = StartupAnalyzer.analyze(s).map { it.type }

    @Test fun `failed step is an ERROR`() {
        val s = session(listOf(step("asr", status = StepStatus.FAILED, error = "boom")))
        assertTrue(types(s).contains(IssueType.ERROR))
    }

    @Test fun `step over slow threshold is SLOW`() {
        val s = session(listOf(step("big", start = 0, end = 80)))
        assertTrue(types(s).contains(IssueType.SLOW))
    }

    @Test fun `fast step is not SLOW`() {
        val s = session(listOf(step("quick", start = 0, end = 30)))
        assertTrue(!types(s).contains(IssueType.SLOW))
    }

    @Test fun `dependency violation when step starts before its dep ends`() {
        val s = session(listOf(
            step("config", start = 0, end = 100),
            step("asr", deps = listOf("config"), start = 40, end = 120)
        ))
        assertTrue(types(s).contains(IssueType.DEP_VIOLATION))
    }

    @Test fun `running step at completion is NEVER_ENDED`() {
        val s = session(listOf(step("hang", end = null, status = StepStatus.RUNNING)))
        assertTrue(types(s).contains(IssueType.NEVER_ENDED))
    }

    @Test fun `dependency cycle is detected`() {
        val s = session(listOf(
            step("a", deps = listOf("b")),
            step("b", deps = listOf("a"))
        ))
        assertTrue(types(s).contains(IssueType.DEP_CYCLE))
    }

    @Test fun `independent step that started late is PARALLELIZABLE`() {
        val s = session(listOf(step("late", deps = emptyList(), start = 80, end = 90)))
        assertTrue(types(s).contains(IssueType.PARALLELIZABLE))
    }
}
