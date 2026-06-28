package com.debugtools.okhttp.capture

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

/**
 * Correlates an in-flight OkHttp [Call] with the captured `HttpRecord` id.
 *
 * The [CapturingInterceptor] links the call to the record id it creates; the
 * [TimingEventListener] then attaches per-phase timing to that exact record on
 * `callEnd`/`callFailed`. This replaces matching by request URL, which mis-attributes
 * timing when several requests share a URL (and never reached failed records reliably).
 */
class CallTimingCorrelator {
    private val callToRecordId = ConcurrentHashMap<Call, String>()

    fun link(call: Call, recordId: String) {
        callToRecordId[call] = recordId
    }

    /** Returns and removes the record id linked to [call], or null if none. */
    fun consume(call: Call): String? = callToRecordId.remove(call)
}
