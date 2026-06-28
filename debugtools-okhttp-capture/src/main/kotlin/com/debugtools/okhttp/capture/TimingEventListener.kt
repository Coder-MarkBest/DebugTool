package com.debugtools.okhttp.capture

import com.debugtools.okhttp.data.Timing
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects per-phase HTTP timings (DNS / Connect / TLS / Send / Wait / Receive)
 * and attaches them to the corresponding [com.debugtools.okhttp.data.HttpRecord]
 * in [NetworkRepository].
 *
 * Because [callEnd] fires AFTER the Interceptor chain has already added the record,
 * timing is attached post-hoc to the record id linked via [CallTimingCorrelator].
 */
class TimingEventListener(
    private val repository: NetworkRepository,
    private val correlator: CallTimingCorrelator? = null
) : EventListener() {

    /** Mutable builder kept while the call is in flight. */
    private class Builder(val startNs: Long) {
        var dnsStartNs: Long? = null; var dnsEndNs: Long? = null
        var connectStartNs: Long? = null; var connectEndNs: Long? = null
        var tlsStartNs: Long? = null; var tlsEndNs: Long? = null
        var requestSendStartNs: Long? = null; var requestSendEndNs: Long? = null
        var responseHeadersStartNs: Long? = null
        var responseBodyEndNs: Long? = null

        fun build(endNs: Long): Timing {
            fun delta(s: Long?, e: Long?): Long? =
                if (s != null && e != null) (e - s) / 1_000_000L else null
            val totalMs = (endNs - startNs) / 1_000_000L
            return Timing(
                dnsMs = delta(dnsStartNs, dnsEndNs),
                connectMs = delta(connectStartNs, connectEndNs),
                tlsMs = delta(tlsStartNs, tlsEndNs),
                requestSendMs = delta(requestSendStartNs, requestSendEndNs),
                waitMs = delta(requestSendEndNs, responseHeadersStartNs),
                responseReceiveMs = delta(responseHeadersStartNs, responseBodyEndNs),
                totalMs = totalMs
            )
        }
    }

    private val builders = ConcurrentHashMap<Call, Builder>()

    class Factory(
        private val repository: NetworkRepository,
        private val correlator: CallTimingCorrelator? = null
    ) : EventListener.Factory {
        override fun create(call: Call): EventListener = TimingEventListener(repository, correlator)
    }

    override fun callStart(call: Call) {
        builders[call] = Builder(startNs = System.nanoTime())
    }

    override fun dnsStart(call: Call, domainName: String) {
        builders[call]?.dnsStartNs = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        builders[call]?.dnsEndNs = System.nanoTime()
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        builders[call]?.connectStartNs = System.nanoTime()
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        builders[call]?.connectEndNs = System.nanoTime()
    }

    override fun secureConnectStart(call: Call) {
        builders[call]?.tlsStartNs = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        builders[call]?.tlsEndNs = System.nanoTime()
    }

    override fun requestHeadersStart(call: Call) {
        builders[call]?.requestSendStartNs = System.nanoTime()
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        // Only set end if body end hasn't been set (GET requests have no body)
        if (builders[call]?.requestSendEndNs == null) {
            builders[call]?.requestSendEndNs = System.nanoTime()
        }
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        builders[call]?.requestSendEndNs = System.nanoTime()
    }

    override fun responseHeadersStart(call: Call) {
        builders[call]?.responseHeadersStartNs = System.nanoTime()
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        builders[call]?.responseBodyEndNs = System.nanoTime()
    }

    override fun callEnd(call: Call) {
        finalize(call)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        finalize(call)
    }

    private fun finalize(call: Call) {
        val builder = builders.remove(call) ?: return
        val timing = builder.build(System.nanoTime())
        val recordId = correlator?.consume(call) ?: return
        repository.attachTiming(recordId, timing)
    }
}
