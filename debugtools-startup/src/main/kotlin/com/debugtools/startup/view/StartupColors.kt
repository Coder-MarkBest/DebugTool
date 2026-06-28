package com.debugtools.startup.view

import com.debugtools.startup.protocol.StepStatus

/** Palette for the startup panel. Raw ARGB ints (no android.graphics.Color). */
object StartupColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val ACCENT = 0xFF2DD4BF.toInt()
    val SUCCESS = 0xFF48BB78.toInt()
    val FAILED = 0xFFF43F5E.toInt()
    val RUNNING = 0xFF64748B.toInt()
    val CRITICAL = 0xFFF6AD55.toInt()
    val EDGE = 0xFF4A5568.toInt()

    fun statusColor(s: StepStatus): Int = when (s) {
        StepStatus.SUCCESS -> SUCCESS
        StepStatus.FAILED -> FAILED
        StepStatus.RUNNING -> RUNNING
    }
}
