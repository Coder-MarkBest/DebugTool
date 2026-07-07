package com.debugtools.conversation

import android.content.Context
import com.debugtools.conversation.trace.TraceOutcome
import com.debugtools.conversation.trace.VoiceTraceEvent
import com.debugtools.conversation.trace.VoiceTraceProfile

object VoiceTrace {
    fun init(context: Context, profile: VoiceTraceProfile) {
        LinkTrace.init(context, profile)
    }

    fun mark(event: VoiceTraceEvent) {
        LinkTrace.markVoiceEvent(event)
    }

    fun begin(requestId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        LinkTrace.begin(requestId, name, attrs)
    }

    fun end(requestId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        LinkTrace.end(requestId, name, attrs)
    }

    fun instant(requestId: String? = null, name: String, attrs: Map<String, String> = emptyMap()) {
        LinkTrace.instant(requestId, name, attrs)
    }

    fun finish(requestId: String? = null, outcome: TraceOutcome = TraceOutcome.SUCCESS) {
        LinkTrace.finish(requestId, outcome)
    }

    internal fun snapshot() = LinkTrace.snapshot()
    internal fun currentProfile() = LinkTrace.currentProfile()
    internal fun currentRecorder() = LinkTrace.currentRecorder()
    internal fun context() = LinkTrace.context()
}
