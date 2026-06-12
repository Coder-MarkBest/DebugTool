package com.debugtools.okhttp.data

/**
 * HTTP request timing breakdown. All phase fields are nullable because
 * EventListener may not be configured (only [totalMs] is always present).
 */
data class Timing(
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val requestSendMs: Long?,
    val waitMs: Long?,             // TTFB
    val responseReceiveMs: Long?,
    val totalMs: Long
)
