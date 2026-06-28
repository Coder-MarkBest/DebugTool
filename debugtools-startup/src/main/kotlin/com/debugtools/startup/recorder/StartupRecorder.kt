package com.debugtools.startup.recorder

import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus

/**
 * Accumulates one startup session. Thread-safe (begin/success/fail may be called
 * from parallel init threads). Pure JVM — [clock] returns a monotonic uptime; no
 * Android dependency, so it is unit-testable with a fake clock.
 */
class StartupRecorder(
    private val sessionId: String,
    private val launchUptimeMs: Long,
    private val startedAtWallMs: Long,
    private val appVersion: String?,
    private val clock: () -> Long
) {
    private val lock = Any()
    private val steps = LinkedHashMap<String, StartupStep>()
    private var completedUptimeMs: Long? = null
    private var completedExplicitly = false

    fun begin(name: String, dependsOn: List<String>) = synchronized(lock) {
        if (steps.containsKey(name)) return
        steps[name] = StartupStep(
            name = name, dependsOn = dependsOn, startUptimeMs = clock(),
            endUptimeMs = null, status = StepStatus.RUNNING, error = null,
            thread = Thread.currentThread().name
        )
    }

    fun success(name: String) = close(name, StepStatus.SUCCESS, null)

    fun fail(name: String, error: String?) = close(name, StepStatus.FAILED, error)

    private fun close(name: String, status: StepStatus, error: String?) = synchronized(lock) {
        val s = steps[name] ?: return
        if (s.endUptimeMs != null) return
        steps[name] = s.copy(endUptimeMs = clock(), status = status, error = error)
    }

    fun complete() = synchronized(lock) {
        if (completedUptimeMs == null) { completedUptimeMs = clock(); completedExplicitly = true }
    }

    fun finalizeFallback() = synchronized(lock) {
        if (completedUptimeMs == null) { completedUptimeMs = clock(); completedExplicitly = false }
    }

    fun isCompleted(): Boolean = synchronized(lock) { completedUptimeMs != null }

    fun snapshot(): StartupSession = synchronized(lock) {
        StartupSession(
            sessionId = sessionId,
            startedAtWallMs = startedAtWallMs,
            launchUptimeMs = launchUptimeMs,
            appVersion = appVersion,
            steps = steps.values.toList(),
            completedUptimeMs = completedUptimeMs,
            completedExplicitly = completedExplicitly
        )
    }
}
