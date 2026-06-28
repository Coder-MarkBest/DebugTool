package com.debugtools.audiomon.view

import com.debugtools.audiomon.anomaly.AnomalyType

/**
 * Central palette for the audio panel. Colors are raw ARGB Ints (no
 * android.graphics.Color) so they are usable from plain unit tests.
 */
object AudioColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val START = 0xFF2DD4BF.toInt()
    val STOP = 0xFFF43F5E.toInt()
    val REPORT = 0xFF3B82F6.toInt()
    val STREAM_A = 0xFFF6AD55.toInt()
    val STREAM_B = 0xFF63B3ED.toInt()
    val ANOMALY = 0xFFFB7185.toInt()

    fun anomalyTypeColor(t: AnomalyType): Int = when (t) {
        AnomalyType.CLIPPING -> 0xFFF43F5E.toInt()
        AnomalyType.SILENCE_DROPOUT -> 0xFF94A3B8.toInt()
        AnomalyType.ENERGY_JUMP -> 0xFFF6AD55.toInt()
        AnomalyType.HIGH_NOISE_FLOOR -> 0xFFA78BFA.toInt()
    }
}
