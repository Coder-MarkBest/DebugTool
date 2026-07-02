package com.debugtools.stability.view

import com.debugtools.stability.protocol.CrashType

object StabilityColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val ACCENT = 0xFF2DD4BF.toInt()
    val ALIVE = 0xFF48BB78.toInt()
    val DEAD = 0xFFF43F5E.toInt()
    val JAVA_CRASH = 0xFFF43F5E.toInt()
    val NATIVE_CRASH = 0xFFF6AD55.toInt()
    val ANR = 0xFFFBBF24.toInt()

    fun crashColor(t: CrashType): Int = when (t) {
        CrashType.JAVA_CRASH -> JAVA_CRASH
        CrashType.NATIVE_CRASH -> NATIVE_CRASH
        CrashType.ANR -> ANR
    }

    fun crashEmoji(t: CrashType): String = when (t) {
        CrashType.JAVA_CRASH -> "💥"
        CrashType.NATIVE_CRASH -> "🪦"
        CrashType.ANR -> "⏱"
    }
}
