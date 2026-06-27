package com.debugtools.audiomon.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class FftProcessorTest {

    /** PCM16 sine wave with [cycles] full periods across [size] samples at amplitude [amp] (0..1). */
    private fun sine(size: Int, cycles: Int, amp: Double = 0.5): ShortArray =
        ShortArray(size) { i ->
            (Short.MAX_VALUE * amp * sin(2.0 * PI * cycles * i / size)).roundToInt().toShort()
        }

    @Test
    fun `computeMagnitudes rejects size mismatch`() {
        val ex = runCatching { FftProcessor.computeMagnitudes(ShortArray(32), 64) }
            .exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `computeMagnitudes returns half-size output`() {
        val mags = FftProcessor.computeMagnitudes(sine(64, 8), 64)
        assertEquals(32, mags.size)
    }

    @Test
    fun `computeMagnitudes peaks at the input frequency bin`() {
        // c full cycles across a 64-sample window -> energy concentrates in bin c.
        // Use a small amplitude so the peak stays below the 0 dB clip and the
        // exact bin is a strict maximum (loud signals saturate adjacent bins to 1.0).
        for (c in listOf(4, 8, 12)) {
            val mags = FftProcessor.computeMagnitudes(sine(64, c, amp = 0.05), 64)
            val peakBin = mags.indices.maxByOrNull { mags[it] }
            assertEquals("cycles=$c", c, peakBin)
        }
    }

    @Test
    fun `computeMagnitudes output stays within 0 to 1`() {
        val mags = FftProcessor.computeMagnitudes(sine(64, 8), 64)
        assertTrue(mags.all { it in 0f..1f })
    }

    @Test
    fun `computeMagnitudes returns all zero for silence`() {
        // Silence is below the -80 dB floor, so every normalized bin clamps to 0.
        val mags = FftProcessor.computeMagnitudes(ShortArray(64), 64)
        assertTrue(mags.all { it == 0f })
    }

    @Test
    fun `computeRms returns zero for empty input`() {
        assertEquals(0f, FftProcessor.computeRms(ShortArray(0)), 0f)
    }

    @Test
    fun `computeRms of full-scale constant is one`() {
        val constant = ShortArray(128) { Short.MAX_VALUE }
        assertEquals(1f, FftProcessor.computeRms(constant), 1e-3f)
    }

    @Test
    fun `computeRms of a sine is amplitude over sqrt2`() {
        // RMS of a sine = peak amplitude / sqrt(2).
        val amp = 0.8
        val rms = FftProcessor.computeRms(sine(1024, 16, amp))
        assertEquals((amp / sqrt(2.0)).toFloat(), rms, 1e-2f)
    }
}
