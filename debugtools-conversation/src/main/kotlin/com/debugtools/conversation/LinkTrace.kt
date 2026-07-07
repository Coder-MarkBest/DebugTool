package com.debugtools.conversation

import android.content.Context
import android.os.SystemClock
import com.debugtools.conversation.trace.LinkTraceEvent
import com.debugtools.conversation.trace.LinkTraceProfile
import com.debugtools.conversation.trace.LinkTraceRecorder
import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.TraceOutcome
import com.debugtools.conversation.trace.VoiceTraceEvent

object LinkTrace {
    @Volatile private var recorder: LinkTraceRecorder? = null
    @Volatile private var profile: LinkTraceProfile? = null
    @Volatile private var appContext: Context? = null

    fun init(profile: LinkTraceProfile) {
        this.profile = profile
        this.recorder = LinkTraceRecorder(profile)
    }

    fun init(context: Context, profile: LinkTraceProfile) {
        appContext = context.applicationContext
        init(profile)
    }

    fun mark(event: LinkTraceEvent) {
        markVoiceEvent(event.toVoiceTraceEvent())
    }

    internal fun markVoiceEvent(event: VoiceTraceEvent) {
        recorder?.record(event)
    }

    fun begin(traceId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(LinkTraceEvent(traceId, name, TraceEventType.BEGIN, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun end(traceId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(LinkTraceEvent(traceId, name, TraceEventType.END, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun instant(traceId: String? = null, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(LinkTraceEvent(traceId, name, TraceEventType.INSTANT, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun finish(traceId: String? = null, outcome: TraceOutcome = TraceOutcome.SUCCESS) {
        val exit = profile?.boundary?.exitEvents?.firstOrNull() ?: "LinkTraceFinish"
        instant(traceId, exit, mapOf("outcome" to outcome.name))
    }

    internal fun snapshot() = recorder?.snapshot()
    internal fun currentProfile() = profile
    internal fun currentRecorder() = recorder
    internal fun context() = appContext
    internal fun snapshotForTest() = snapshot()
}
