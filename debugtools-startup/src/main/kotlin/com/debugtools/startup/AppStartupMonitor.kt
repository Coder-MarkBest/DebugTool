package com.debugtools.startup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.recorder.StartupRecorder
import com.debugtools.startup.store.StartupStore
import java.io.File
import java.util.UUID

/**
 * Process-wide entry point. Host calls [init] early in Application.onCreate, then
 * reports steps via [begin]/[success]/[fail]/[track] and marks the end with [complete].
 * If [complete] is never called, the first Activity's onResume finalizes the session
 * as a safety net so data is still persisted.
 *
 * Recording works even if [init] was not called yet (a recorder is lazily created);
 * persistence and the lifecycle fallback only activate after [init].
 */
object AppStartupMonitor {

    private const val FALLBACK_DELAY_MS = 1000L

    private val lock = Any()
    private var recorder: StartupRecorder? = null
    private var store: StartupStore? = null
    @Volatile private var persisted = false

    private fun recorder(): StartupRecorder = synchronized(lock) {
        recorder ?: StartupRecorder(
            sessionId = UUID.randomUUID().toString(),
            launchUptimeMs = SystemClock.uptimeMillis(),
            startedAtWallMs = System.currentTimeMillis(),
            appVersion = null,
            clock = { SystemClock.uptimeMillis() }
        ).also { recorder = it }
    }

    /** Wire persistence + the onResume fallback. Idempotent. */
    fun init(context: Context, appVersion: String? = null) {
        synchronized(lock) {
            if (store != null) return
            if (recorder == null) {
                recorder = StartupRecorder(
                    sessionId = UUID.randomUUID().toString(),
                    launchUptimeMs = Process.getStartUptimeMillis(),
                    startedAtWallMs = System.currentTimeMillis(),
                    appVersion = appVersion,
                    clock = { SystemClock.uptimeMillis() }
                )
            }
            store = StartupStore(File(context.applicationContext.filesDir, "startup"))
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(fallback)
    }

    fun begin(name: String, dependsOn: List<String> = emptyList()) = recorder().begin(name, dependsOn)
    fun success(name: String) = recorder().success(name)
    fun fail(name: String, error: Throwable) =
        recorder().fail(name, "${error.javaClass.simpleName}: ${error.message ?: "(no message)"}")
    fun fail(name: String, errorMessage: String) = recorder().fail(name, errorMessage)

    inline fun <T> track(name: String, dependsOn: List<String> = emptyList(), block: () -> T): T {
        begin(name, dependsOn)
        return try {
            val r = block(); success(name); r
        } catch (e: Throwable) {
            fail(name, e); throw e
        }
    }

    /** Marks "startup complete": finalize + persist. */
    fun complete() {
        recorder().complete()
        persist()
    }

    /** Same-process accessor for the module to show the current (possibly in-flight) session. */
    fun currentSession(): StartupSession? = synchronized(lock) { recorder?.snapshot() }

    private fun persist() {
        val r: StartupRecorder
        val s: StartupStore
        synchronized(lock) {
            if (persisted) return
            r = recorder ?: return
            s = store ?: return
            persisted = true
        }
        s.save(r.snapshot())
    }

    private val fallback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            Handler(Looper.getMainLooper()).postDelayed({
                val r = synchronized(lock) { recorder }
                if (r != null && !r.isCompleted()) r.finalizeFallback()
                persist()
            }, FALLBACK_DELAY_MS)
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
