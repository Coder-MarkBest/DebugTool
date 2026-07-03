package com.debugtools.conversation

import android.content.Context
import android.os.SystemClock
import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.TraceOutcome
import com.debugtools.conversation.trace.VoiceTraceEvent
import com.debugtools.conversation.trace.VoiceTraceProfile
import com.debugtools.conversation.trace.VoiceTraceRecorder

object VoiceTrace {
    @Volatile private var recorder: VoiceTraceRecorder? = null
    @Volatile private var profile: VoiceTraceProfile? = null
    @Volatile private var appContext: Context? = null

    fun init(context: Context, profile: VoiceTraceProfile) {
        this.appContext = context.applicationContext
        this.profile = profile
        this.recorder = VoiceTraceRecorder(profile)
    }

    fun mark(event: VoiceTraceEvent) {
        recorder?.record(event)
    }

    fun begin(requestId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(VoiceTraceEvent(requestId, name, TraceEventType.BEGIN, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun end(requestId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(VoiceTraceEvent(requestId, name, TraceEventType.END, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun instant(requestId: String? = null, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(VoiceTraceEvent(requestId, name, TraceEventType.INSTANT, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun finish(requestId: String? = null, outcome: TraceOutcome = TraceOutcome.SUCCESS) {
        val exit = profile?.boundary?.exitEvents?.firstOrNull() ?: "VoiceTraceFinish"
        instant(requestId, exit, mapOf("outcome" to outcome.name))
    }

    internal fun snapshot() = recorder?.snapshot()
    internal fun currentProfile() = profile
    internal fun context() = appContext
}
