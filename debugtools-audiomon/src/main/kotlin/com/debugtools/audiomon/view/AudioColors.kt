package com.debugtools.audiomon.view

import com.debugtools.audiomon.anomaly.AnomalyType

/**
 * Central palette + spectrogram colormap for the audio panel. Colors are raw
 * ARGB Ints (no android.graphics.Color), so the LUT is unit-testable on the JVM.
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

    // 5-stop spectrogram ramp: bg -> blue -> teal -> yellow -> red
    private val LUT = intArrayOf(
        0xFF15151F.toInt(), 0xFF2B4B8C.toInt(), 0xFF2DD4BF.toInt(),
        0xFFFACC15.toInt(), 0xFFFB7185.toInt()
    )

    /** Map magnitude level 0..1 to a spectrogram color (opaque). */
    fun spectrogramColor(level: Float): Int {
        val l = level.coerceIn(0f, 1f)
        val pos = l * (LUT.size - 1)
        val i = pos.toInt().coerceAtMost(LUT.size - 2)
        val f = pos - i
        val c0 = LUT[i]
        val c1 = LUT[i + 1]
        val r = lerp((c0 shr 16) and 0xFF, (c1 shr 16) and 0xFF, f)
        val g = lerp((c0 shr 8) and 0xFF, (c1 shr 8) and 0xFF, f)
        val b = lerp(c0 and 0xFF, c1 and 0xFF, f)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun anomalyTypeColor(t: AnomalyType): Int = when (t) {
        AnomalyType.CLIPPING -> 0xFFF43F5E.toInt()
        AnomalyType.SILENCE_DROPOUT -> 0xFF94A3B8.toInt()
        AnomalyType.ENERGY_JUMP -> 0xFFF6AD55.toInt()
        AnomalyType.HIGH_NOISE_FLOOR -> 0xFFA78BFA.toInt()
    }

    private fun lerp(a: Int, b: Int, f: Float): Int =
        (a + (b - a) * f).toInt().coerceIn(0, 255)
}
