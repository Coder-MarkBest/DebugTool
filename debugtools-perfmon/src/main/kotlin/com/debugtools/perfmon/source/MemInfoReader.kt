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
        val m = info.firstOrNull()
        Memory(
            totalPssKb = m?.totalPss ?: 0,
            dalvikPssKb = m?.dalvikPss ?: 0,
            nativePssKb = m?.nativePss ?: 0,
            otherPssKb = m?.otherPss ?: 0
        )
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        // getProcessMemoryInfo is not implemented in test environments (e.g. Robolectric);
        // return zero-filled memory to signal "unavailable but not an error".
        Memory(totalPssKb = 0, dalvikPssKb = 0, nativePssKb = 0, otherPssKb = 0)
    }
}
