package com.debugtools.perfmon.source

import android.app.ActivityManager
import android.content.Context

/**
 * Wraps [ActivityManager.getProcessMemoryInfo] to fetch precise PSS / Java heap /
 * Native heap for a single pid. Returns null when the call throws or returns no info
 * (process gone, permission denied, etc).
 *
 * Single-shot call is 50–200ms for typical apps; only use from Tier 2 sampler.
 */
class MemInfoReader(context: Context) {
    private val am = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    data class Memory(
        val totalPssKb: Int,
        val dalvikPssKb: Int,
        val nativePssKb: Int,
        val otherPssKb: Int
    )

    fun read(pid: Int): Memory? = try {
        val info = am.getProcessMemoryInfo(intArrayOf(pid))
        if (info.isEmpty()) null else {
            val m = info[0]
            Memory(
                totalPssKb = m.totalPss,
                dalvikPssKb = m.dalvikPss,
                nativePssKb = m.nativePss,
                otherPssKb = m.otherPss
            )
        }
    } catch (_: Exception) {
        null
    }
}
