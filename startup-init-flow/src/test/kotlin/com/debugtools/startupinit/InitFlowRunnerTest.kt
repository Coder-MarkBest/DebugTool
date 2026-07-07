package com.debugtools.startupinit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitFlowRunnerTest {
    @Test
    fun `independent tasks run before dependent tasks`() = runTest {
        val events = mutableListOf<String>()

        val result = StartupInitFlow.builder()
            .task("config") { suspendInit { events += "config" } }
            .task("net") { suspendInit { events += "net" } }
            .task("asr", dependsOn = listOf("config")) { suspendInit { events += "asr" } }
            .run()

        assertTrue(result.success)
        assertTrue(events.indexOf("asr") > events.indexOf("config"))
        assertEquals(setOf("config", "net", "asr"), events.toSet())
    }

    @Test
    fun `failed dependency skips downstream and independent branch continues`() = runTest {
        val events = mutableListOf<String>()

        val result = StartupInitFlow.builder()
            .task("config") { suspendInit { error("missing config") } }
            .task("asr", dependsOn = listOf("config")) { suspendInit { events += "asr" } }
            .task("net") { suspendInit { events += "net" } }
            .run()

        assertFalse(result.success)
        assertEquals(listOf("net"), events)
        assertEquals(InitTaskStatus.FAILED, result.taskResults.first { it.name == "config" }.status)
        assertEquals(InitTaskStatus.SKIPPED, result.taskResults.first { it.name == "asr" }.status)
        assertEquals(InitTaskStatus.SUCCESS, result.taskResults.first { it.name == "net" }.status)
    }

    @Test
    fun `dependent task starts as soon as dependency succeeds without waiting for unrelated task`() = runTest {
        val netStarted = CompletableDeferred<Unit>()
        val netRelease = CompletableDeferred<Unit>()
        val asrStarted = CompletableDeferred<Unit>()
        val flow = async {
            StartupInitFlow.builder()
                .task("config") { suspendInit { } }
                .task("net") {
                    suspendInit {
                        netStarted.complete(Unit)
                        netRelease.await()
                    }
                }
                .task("asr", dependsOn = listOf("config")) {
                    suspendInit { asrStarted.complete(Unit) }
                }
                .run()
        }

        netStarted.await()
        withTimeout(100) {
            asrStarted.await()
        }
        assertFalse(flow.isCompleted)

        netRelease.complete(Unit)
        val result = flow.await()
        assertTrue(result.success)
    }

    @Test
    fun `callback task waits for done and ignores repeated done`() = runTest {
        val doneRef = CompletableDeferred<(Throwable?) -> Unit>()
        val flow = async {
            StartupInitFlow.builder()
                .task("callback") {
                    callbackInit { done -> doneRef.complete(done) }
                }
                .run()
        }

        val done = doneRef.await()
        assertFalse(flow.isCompleted)
        done(null)
        done(IllegalStateException("late failure"))

        val result = flow.await()
        assertTrue(result.success)
        assertEquals(InitTaskStatus.SUCCESS, result.taskResults.single().status)
    }

    @Test
    fun `invalid graph returns failed result without running tasks`() = runTest {
        var ran = false

        val result = StartupInitFlow.builder()
            .task("asr", dependsOn = listOf("missing")) { suspendInit { ran = true } }
            .run()

        assertFalse(ran)
        assertFalse(result.success)
        assertEquals("init_flow_invalid_graph", result.taskResults.single().name)
        assertEquals(InitTaskStatus.FAILED, result.taskResults.single().status)
    }
}
