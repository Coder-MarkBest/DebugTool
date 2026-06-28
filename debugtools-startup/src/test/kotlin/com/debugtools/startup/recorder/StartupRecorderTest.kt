package com.debugtools.startup.recorder

import com.debugtools.startup.protocol.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecorderTest {

    private class Clock(var now: Long = 0L) { fun read() = now }

    private fun recorder(clock: Clock) = StartupRecorder(
        sessionId = "s1", launchUptimeMs = 0L, startedAtWallMs = 1000L,
        appVersion = "1.0", clock = clock::read
    )

    @Test fun `begin then success records a completed step`() {
        val c = Clock(); val r = recorder(c)
        c.now = 5; r.begin("config", emptyList())
        c.now = 15; r.success("config")
        val s = r.snapshot().steps.single()
        assertEquals("config", s.name)
        assertEquals(5L, s.startUptimeMs)
        assertEquals(15L, s.endUptimeMs)
        assertEquals(StepStatus.SUCCESS, s.status)
    }

    @Test fun `fail records FAILED with error and ends the step`() {
        val c = Clock(); val r = recorder(c)
        r.begin("asr", listOf("config"))
        r.fail("asr", "BootException: boom")
        val s = r.snapshot().steps.single()
        assertEquals(StepStatus.FAILED, s.status)
        assertTrue(s.error!!.contains("boom"))
        assertEquals(listOf("config"), s.dependsOn)
    }

    @Test fun `running step has null end and RUNNING status`() {
        val r = recorder(Clock())
        r.begin("net", emptyList())
        val s = r.snapshot().steps.single()
        assertNull(s.endUptimeMs)
        assertEquals(StepStatus.RUNNING, s.status)
    }

    @Test fun `duplicate begin and success on unknown or already-ended step are ignored`() {
        val c = Clock(); val r = recorder(c)
        c.now = 1; r.begin("a", emptyList())
        c.now = 2; r.begin("a", emptyList())
        c.now = 3; r.success("a")
        c.now = 4; r.success("a")
        r.success("ghost")
        val s = r.snapshot().steps.single()
        assertEquals(1L, s.startUptimeMs)
        assertEquals(3L, s.endUptimeMs)
    }

    @Test fun `complete marks explicit completion and is idempotent`() {
        val c = Clock(); val r = recorder(c)
        c.now = 9; r.complete()
        c.now = 20; r.complete()
        val snap = r.snapshot()
        assertEquals(9L, snap.completedUptimeMs)
        assertTrue(snap.completedExplicitly)
    }

    @Test fun `finalizeFallback completes without explicit flag and never overrides complete`() {
        val c = Clock(); val r = recorder(c)
        c.now = 7; r.finalizeFallback()
        assertEquals(7L, r.snapshot().completedUptimeMs)
        assertEquals(false, r.snapshot().completedExplicitly)
        c.now = 12; r.complete()
        assertEquals(7L, r.snapshot().completedUptimeMs)
    }

    @Test fun `steps keep insertion order`() {
        val r = recorder(Clock())
        r.begin("a", emptyList()); r.begin("b", emptyList()); r.begin("c", emptyList())
        assertEquals(listOf("a", "b", "c"), r.snapshot().steps.map { it.name })
    }
}
