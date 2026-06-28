package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CriticalPathTest {

    private fun step(name: String, deps: List<String>, start: Long, end: Long?) =
        StartupStep(name, deps, start, end,
            if (end == null) StepStatus.RUNNING else StepStatus.SUCCESS, null, "t")

    private fun session(vararg steps: StartupStep) = StartupSession(
        "s", 0L, 0L, null, steps.toList(), steps.mapNotNull { it.endUptimeMs }.maxOrNull(), true
    )

    @Test fun `path walks back via the latest-finishing dependency`() {
        val s = session(
            step("config", emptyList(), 0, 10),
            step("asr", listOf("config"), 10, 40),
            step("tts", listOf("asr"), 40, 80),
            step("net", emptyList(), 0, 20)
        )
        assertEquals(listOf("config", "asr", "tts"), CriticalPath.of(s))
    }

    @Test fun `no-dependency terminal yields a single element`() {
        val s = session(step("only", emptyList(), 0, 30))
        assertEquals(listOf("only"), CriticalPath.of(s))
    }

    @Test fun `empty session yields empty path`() {
        assertEquals(emptyList<String>(), CriticalPath.of(session()))
    }
}
