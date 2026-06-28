package com.debugtools.sample

import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus

/**
 * Five varied sample startup sessions, one per diagnostic shape, for the demo's
 * 「启动链路」 tab — written straight into the store so the list/gantt/DAG/diagnostics
 * are populated without restarting the app ten times.
 */
object SampleStartupSessions {

    fun all(baseWallMs: Long): List<StartupSession> = listOf(
        allSuccess(baseWallMs + 1),
        withFailure(baseWallMs + 2),
        slowAndDepViolation(baseWallMs + 3),
        neverEnded(baseWallMs + 4),
        parallelizable(baseWallMs + 5)
    )

    private fun step(name: String, deps: List<String>, start: Long, end: Long?, status: StepStatus, error: String? = null) =
        StartupStep(name, deps, start, end, status, error, "demo")

    /** All success, all fast — the clean baseline (no issues). */
    private fun allSuccess(wall: Long) = StartupSession(
        sessionId = "sample-ok", startedAtWallMs = wall, launchUptimeMs = 1000L, appVersion = "1.0",
        steps = listOf(
            step("config", emptyList(), 1000, 1010, StepStatus.SUCCESS),
            step("asr", listOf("config"), 1010, 1030, StepStatus.SUCCESS),
            step("tts", listOf("asr"), 1030, 1045, StepStatus.SUCCESS)
        ),
        completedUptimeMs = 1045L, completedExplicitly = true
    )

    /** nlu fails -> ERROR. */
    private fun withFailure(wall: Long) = StartupSession(
        sessionId = "sample-fail", startedAtWallMs = wall, launchUptimeMs = 2000L, appVersion = "1.0",
        steps = listOf(
            step("config", emptyList(), 2000, 2015, StepStatus.SUCCESS),
            step("asr", listOf("config"), 2015, 2055, StepStatus.SUCCESS),
            step("nlu", listOf("config"), 2015, 2025, StepStatus.FAILED, "IllegalStateException: 模型文件缺失"),
            step("tts", listOf("asr"), 2055, 2075, StepStatus.SUCCESS)
        ),
        completedUptimeMs = 2075L, completedExplicitly = true
    )

    /** config 100ms (SLOW); db starts before config finished (DEP_VIOLATION) and is 70ms (SLOW). */
    private fun slowAndDepViolation(wall: Long) = StartupSession(
        sessionId = "sample-slow", startedAtWallMs = wall, launchUptimeMs = 3000L, appVersion = "1.0",
        steps = listOf(
            step("config", emptyList(), 3000, 3100, StepStatus.SUCCESS),
            step("db", listOf("config"), 3050, 3120, StepStatus.SUCCESS)
        ),
        completedUptimeMs = 3120L, completedExplicitly = true
    )

    /** worker never ends -> NEVER_ENDED; session finalized by fallback (not explicit). */
    private fun neverEnded(wall: Long) = StartupSession(
        sessionId = "sample-hang", startedAtWallMs = wall, launchUptimeMs = 4000L, appVersion = "1.0",
        steps = listOf(
            step("config", emptyList(), 4000, 4020, StepStatus.SUCCESS),
            step("worker", listOf("config"), 4020, null, StepStatus.RUNNING)
        ),
        completedUptimeMs = 4200L, completedExplicitly = false
    )

    /** late has no deps yet starts 80ms after launch -> PARALLELIZABLE. */
    private fun parallelizable(wall: Long) = StartupSession(
        sessionId = "sample-parallel", startedAtWallMs = wall, launchUptimeMs = 5000L, appVersion = "1.0",
        steps = listOf(
            step("config", emptyList(), 5000, 5020, StepStatus.SUCCESS),
            step("late", emptyList(), 5080, 5100, StepStatus.SUCCESS)
        ),
        completedUptimeMs = 5100L, completedExplicitly = true
    )
}
