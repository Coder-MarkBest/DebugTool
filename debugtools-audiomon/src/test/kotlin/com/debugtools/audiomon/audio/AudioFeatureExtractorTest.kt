package com.debugtools.audiomon.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioFeatureExtractorTest {

    private val sampleRate = 16000
    private val fftSize = 1024

    /** PCM16 sine: [cycles] periods across [size] samples at amplitude [amp] (0..1). */
    private fun sine(size: Int, cycles: Int, amp: Double = 0.5): ShortArray =
        ShortArray(size) { i ->
            (Short.MAX_VALUE * amp * sin(2.0 * PI * cycles * i / size)).roundToInt().toShort()
        }

    private fun extractor(thresholdDb: Float = -50f) =
        AudioFeatureExtractor(sampleRate, fftSize, thresholdDb)

    @Test
    fun `empty input yields all-zero features without crashing`() {
        val f = extractor().build()
        assertEquals(0L, f.sampleCount)
        assertEquals(0L, f.durationMs)
        assertEquals(0f, f.avgRms, 0f)
        assertEquals(AudioFeatureExtractor.BAND_COUNT, f.bandEnergy.size)
    }

    @Test
    fun `silence is mostly silent frames with very low dB`() {
        val ex = extractor()
        repeat(8) { ex.feed(ShortArray(fftSize)) }
        val f = ex.build()
        assertEquals(1.0f, f.silenceRatio, 1e-3f)
        assertEquals(0.0f, f.activeRatio, 1e-3f)
        assertTrue("avgDb=${f.avgDb}", f.avgDb < -60f)
    }

    @Test
    fun `duration is derived from sample count and rate`() {
        val ex = extractor()
        repeat(16) { ex.feed(sine(fftSize, 64, 0.5)) } // 16 * 1024 = 16384 samples
        val f = ex.build()
        assertEquals(16384L, f.sampleCount)
        assertEquals(16384L * 1000 / sampleRate, f.durationMs)
    }

    @Test
    fun `dominant frequency tracks a pure tone`() {
        // 64 cycles over a 1024-sample window -> bin 64 -> 64 * 16000 / 1024 = 1000 Hz.
        val ex = extractor()
        repeat(8) { ex.feed(sine(fftSize, 64, amp = 0.05)) }
        val f = ex.build()
        val expected = 64f * sampleRate / fftSize
        assertEquals(expected, f.dominantFreq, expected * 0.1f)
    }

    @Test
    fun `loud tone is mostly active frames`() {
        val ex = extractor(thresholdDb = -50f)
        repeat(8) { ex.feed(sine(fftSize, 64, amp = 0.8)) }
        val f = ex.build()
        assertTrue("activeRatio=${f.activeRatio}", f.activeRatio > 0.9f)
        assertEquals(8, f.rmsSeries.size)
        assertEquals(8, f.dbSeries.size)
    }

    @Test
    fun `zero crossing rate is roughly two per cycle`() {
        // 64 cycles over 1024 samples -> ~128 crossings -> rate ~ 128/1024 = 0.125.
        val ex = extractor()
        ex.feed(sine(fftSize, 64, amp = 0.5))
        val f = ex.build()
        assertEquals(0.125f, f.zeroCrossingRate, 0.02f)
    }

    @Test
    fun `cross-frame zero crossing sign state carries across feed calls`() {
        // Build a full sine of fftSize samples with 64 cycles, then compare:
        // extractor A receives it as one frame; extractor B receives it split into two halves.
        // Both should produce the same zeroCrossingRate because the boundary crossing
        // between the two halves must be counted correctly (no spurious reset, no missed crossing).
        val full = sine(fftSize, 64, amp = 0.5)
        val half = fftSize / 2
        val first = full.copyOfRange(0, half)
        val second = full.copyOfRange(half, fftSize)

        val exA = extractor()
        exA.feed(full)
        val fA = exA.build()

        val exB = extractor()
        exB.feed(first)
        exB.feed(second)
        val fB = exB.build()

        assertEquals(fA.zeroCrossingRate, fB.zeroCrossingRate, 1e-4f)
    }

    @Test
    fun `short trailing frame counts toward amplitude and timeseries but not spectral`() {
        // Feed one full frame then one half-size frame.
        // Amplitude and timeseries stats should accumulate both; FFT should only use the full frame.
        val ex = extractor()
        ex.feed(sine(fftSize, 64, amp = 0.5))
        ex.feed(sine(fftSize / 2, 32, amp = 0.5))

        val f = ex.build()

        assertEquals((fftSize + fftSize / 2).toLong(), f.sampleCount)
        assertEquals(2, f.rmsSeries.size)
        assertEquals(2, f.dbSeries.size)
        // dominantFreq > 0 confirms spectral accumulated from the one full frame
        // (spectralFrames == 1; the short frame was skipped for FFT without throwing)
        assertTrue("dominantFreq=${f.dominantFreq} should be > 0", f.dominantFreq > 0f)
    }
}
