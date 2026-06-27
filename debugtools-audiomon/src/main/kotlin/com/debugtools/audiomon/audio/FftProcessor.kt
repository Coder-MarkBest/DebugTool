package com.debugtools.audiomon.audio

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.PI

/**
 * Pure-function radix-2 Cooley-Tukey FFT processor.
 *
 * Operates on PCM16 input and returns normalized magnitude values suitable
 * for spectrum visualization.
 */
object FftProcessor {

    private const val MIN_DB = -80f

    /**
     * Compute FFT magnitudes from PCM16 samples.
     *
     * @param samples Must be exactly [fftSize] length, a power of 2.
     * @param fftSize The FFT window size (must match samples.length).
     * @return FloatArray of size fftSize/2, values normalized to 0..1 range (dB-scaled).
     */
    fun computeMagnitudes(samples: ShortArray, fftSize: Int): FloatArray {
        require(samples.size == fftSize) { "Expected $fftSize samples, got ${samples.size}" }

        // Normalize PCM16 to -1..1 float
        val real = FloatArray(fftSize) { samples[it].toFloat() / Short.MAX_VALUE }
        val imag = FloatArray(fftSize)

        // Apply Hanning window to reduce spectral leakage
        for (i in 0 until fftSize) {
            val w = (0.5 * (1 - cos(2.0 * PI * i / fftSize))).toFloat()
            real[i] *= w
        }

        // In-place radix-2 FFT
        fft(real, imag, fftSize)

        // Compute magnitudes in dB
        val halfSize = fftSize / 2
        val magnitudes = FloatArray(halfSize)
        for (i in 0 until halfSize) {
            val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
            magnitudes[i] = 20f * log10(mag.coerceAtLeast(1e-10f))
        }

        // Normalize dB to 0..1 for display
        for (i in 0 until halfSize) {
            magnitudes[i] = ((magnitudes[i] - MIN_DB) / (-MIN_DB)).coerceIn(0f, 1f)
        }

        return magnitudes
    }

    /**
     * Compute RMS amplitude from PCM16 samples.
     * @return RMS value normalized to 0..1.
     */
    fun computeRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) {
            val f = s.toFloat() / Short.MAX_VALUE
            sumSq += f * f
        }
        return sqrt(sumSq / samples.size).toFloat()
    }

    private fun fft(real: FloatArray, imag: FloatArray, n: Int) {
        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        // Cooley-Tukey butterfly
        var step = 2
        while (step <= n) {
            val halfStep = step shr 1
            val angleStep = -(2.0 * PI / step).toFloat()
            for (i in 0 until n step step) {
                for (k in 0 until halfStep) {
                    val angle = angleStep * k
                    val wr = cos(angle.toDouble()).toFloat()
                    val wi = kotlin.math.sin(angle.toDouble()).toFloat()
                    val idx = i + k
                    val idxHalf = idx + halfStep
                    val tr = wr * real[idxHalf] - wi * imag[idxHalf]
                    val ti = wr * imag[idxHalf] + wi * real[idxHalf]
                    real[idxHalf] = real[idx] - tr
                    imag[idxHalf] = imag[idx] - ti
                    real[idx] = real[idx] + tr
                    imag[idx] = imag[idx] + ti
                }
            }
            step = step shl 1
        }
    }
}
