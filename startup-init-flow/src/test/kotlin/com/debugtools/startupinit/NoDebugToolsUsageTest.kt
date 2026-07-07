package com.debugtools.startupinit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoDebugToolsUsageTest {
    @Test
    fun `core package exposes no debugtools dependency names`() {
        val apiClassNames = listOf(
            InitFlowResult::class.java.name,
            InitTaskResult::class.java.name,
            InitTaskStatus::class.java.name,
            InitReporter::class.java.name,
            NoOpInitReporter::class.java.name
        )

        assertTrue(apiClassNames.all { it.startsWith("com.debugtools.startupinit") })
        assertFalse(apiClassNames.any { it.contains(".startup.") || it.contains(".core.") })
    }

    @Test
    fun `result success is true only when all tasks succeed`() {
        val ok = InitFlowResult(
            taskResults = listOf(InitTaskResult("config", InitTaskStatus.SUCCESS)),
            success = true
        )
        val failed = InitFlowResult(
            taskResults = listOf(InitTaskResult("asr", InitTaskStatus.FAILED, "boom")),
            success = false
        )

        assertEquals("config", ok.taskResults.single().name)
        assertTrue(ok.success)
        assertFalse(failed.success)
        assertEquals("boom", failed.taskResults.single().error)
    }
}
