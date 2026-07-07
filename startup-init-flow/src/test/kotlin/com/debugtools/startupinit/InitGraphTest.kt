package com.debugtools.startupinit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitGraphTest {
    @Test
    fun `validate rejects duplicate task names`() {
        val graph = InitGraph(listOf(task("config"), task("config")))

        val result = graph.validate()

        assertFalse(result.valid)
        assertEquals("Duplicate init task name: config", result.error)
    }

    @Test
    fun `validate rejects missing dependency`() {
        val graph = InitGraph(listOf(task("asr", dependsOn = listOf("config"))))

        val result = graph.validate()

        assertFalse(result.valid)
        assertEquals("Task asr depends on missing task config", result.error)
    }

    @Test
    fun `validate rejects dependency cycle`() {
        val graph = InitGraph(
            listOf(
                task("config", dependsOn = listOf("asr")),
                task("asr", dependsOn = listOf("config"))
            )
        )

        val result = graph.validate()

        assertFalse(result.valid)
        assertTrue(result.error!!.contains("Dependency cycle"))
    }

    @Test
    fun `ready tasks include only tasks whose dependencies completed`() {
        val config = task("config")
        val asr = task("asr", dependsOn = listOf("config"))
        val tts = task("tts", dependsOn = listOf("asr"))
        val net = task("net")
        val graph = InitGraph(listOf(config, asr, tts, net))

        assertEquals(
            listOf("config", "net"),
            graph.readyTasks(emptySet(), emptySet(), emptySet()).map { it.name }
        )
        assertEquals(
            listOf("asr"),
            graph.readyTasks(setOf("config", "net"), emptySet(), setOf("config", "net")).map { it.name }
        )
    }

    @Test
    fun `blocked by failures returns downstream tasks with failed dependencies`() {
        val graph = InitGraph(
            listOf(
                task("config"),
                task("asr", dependsOn = listOf("config")),
                task("tts", dependsOn = listOf("asr"))
            )
        )

        val blocked = graph.blockedByFailures(
            failedOrSkipped = setOf("config"),
            completed = emptySet(),
            alreadyResolved = setOf("config")
        )

        assertEquals(listOf("asr"), blocked.keys.map { it.name })
        assertEquals(listOf("config"), blocked.values.single())
    }

    private fun task(name: String, dependsOn: List<String> = emptyList()) =
        InitTask(name, dependsOn) {}
}
