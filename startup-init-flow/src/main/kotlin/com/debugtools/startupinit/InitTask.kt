package com.debugtools.startupinit

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean

internal data class InitTask(
    val name: String,
    val dependsOn: List<String>,
    val runner: suspend () -> Unit
)

class InitTaskBuilder {
    private var runner: (suspend () -> Unit)? = null

    fun runBlockingInit(block: () -> Unit) {
        runner = { block() }
    }

    fun suspendInit(block: suspend () -> Unit) {
        runner = block
    }

    fun callbackInit(block: (done: (Throwable?) -> Unit) -> Unit) {
        runner = {
            suspendCancellableCoroutine { continuation ->
                val resumed = AtomicBoolean(false)
                block { error ->
                    if (!resumed.compareAndSet(false, true)) return@block
                    if (error == null) {
                        continuation.resume(Unit)
                    } else {
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    internal fun buildRunner(): suspend () -> Unit =
        requireNotNull(runner) { "Init task must define runBlockingInit, suspendInit, or callbackInit" }
}
