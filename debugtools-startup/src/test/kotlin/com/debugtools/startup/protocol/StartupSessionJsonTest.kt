package com.debugtools.startup.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupSessionJsonTest {

    private fun sample() = StartupSession(
        sessionId = "s1",
        startedAtWallMs = 1000L,
        launchUptimeMs = 50L,
        appVersion = "1.2.3",
        steps = listOf(
            StartupStep("config", emptyList(), 60L, 70L, StepStatus.SUCCESS, null, "main"),
            StartupStep("asr", listOf("config"), 72L, 130L, StepStatus.FAILED, "BootException: boom", "init-1"),
            StartupStep("net", emptyList(), 60L, null, StepStatus.RUNNING, null, "io-2")
        ),
        completedUptimeMs = 130L,
        completedExplicitly = true
    )

    @Test fun `session round-trips through json`() {
        val json = sample().toJson()
        val back = StartupSession.fromJson(JSONObject(json.toString()))
        assertEquals(sample(), back)
    }

    @Test fun `nulls survive round-trip`() {
        val back = StartupSession.fromJson(JSONObject(sample().toJson().toString()))
        val running = back.steps.first { it.name == "net" }
        assertNull(running.endUptimeMs)
        assertNull(running.error)
        assertEquals(StepStatus.RUNNING, running.status)
    }
}
