package com.debugtools.conversation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.recorder.ConversationRecorder
import com.debugtools.conversation.store.ConversationStore
import java.io.File
import java.util.UUID

/**
 * Process-wide entry point for conversation trace. The host adapter layer maps
 * its existing conversation logs to [ConversationTurn] and calls [submitTurn].
 *
 * ```kotlin
 * ConversationTracer.init(context)
 * // optional: ConversationTracer.startSession(id, metadata)
 * ConversationTracer.submitTurn(adaptLogToTurn(rawLog))
 * ConversationTracer.endSession(id)
 * ```
 *
 * If [startSession] is not called, the recorder is lazily created on first [submitTurn].
 * If [endSession] is never called, the first Activity's onResume auto-finalizes.
 */
object ConversationTracer {

    private const val FALLBACK_DELAY_MS = 1000L

    private val lock = Any()
    private var recorder: ConversationRecorder? = null
    private var store: ConversationStore? = null
    @Volatile private var persisted = false

    private fun recorder(): ConversationRecorder = synchronized(lock) {
        recorder ?: newRecorder(UUID.randomUUID().toString()).also { recorder = it }
    }

    private fun newRecorder(sessionId: String) = ConversationRecorder(
        sessionId = sessionId,
        startedAtWallMs = System.currentTimeMillis(),
        clock = { System.currentTimeMillis() }
    )

    /** Wire persistence + the onResume fallback. Idempotent. */
    fun init(context: Context) {
        synchronized(lock) {
            if (store != null) return
            store = ConversationStore(File(context.applicationContext.filesDir, "conversation"))
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(fallback)
    }

    fun startSession(sessionId: String, metadata: Map<String, String>? = null) {
        synchronized(lock) {
            if (recorder != null && recorder?.snapshot()?.sessionId == sessionId) {
                recorder?.startSession(metadata)
                return
            }
            // New session: persist the old one if any, then create fresh
            persist()
            recorder = newRecorder(sessionId).also { it.startSession(metadata) }
            persisted = false
        }
    }

    fun submitTurn(turn: ConversationTurn) = recorder().submitTurn(turn)

    fun endSession(sessionId: String) {
        recorder().endSession()
        persist()
    }

    fun currentSession(): ConversationSession? = synchronized(lock) { recorder?.snapshot() }

    fun loadSessions(): List<ConversationSession> {
        val s = synchronized(lock) { store } ?: return emptyList()
        return s.load()
    }

    private fun persist() {
        val r: ConversationRecorder
        val s: ConversationStore
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
            if (persisted) return
            Handler(Looper.getMainLooper()).postDelayed({
                val r = synchronized(lock) { recorder }
                if (r != null && !r.isEnded()) r.finalizeFallback()
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
