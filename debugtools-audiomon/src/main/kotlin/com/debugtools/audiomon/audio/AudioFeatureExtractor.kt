package com.debugtools.audiomon.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * Streaming numerical-feature accumulator for a single PCM16 mono stream.
 *
 * Call [feed] per frame during recording (constant memory — running sums plus
 * one float per frame for the timeseries), then [build] to aggregate. Pure JVM;
 * no Android dependencies. Frame size should equal [fftSize] for spectral
 * features to accumulate; shorter trailing frames still count toward amplitude
 * and timeseries stats.
 */
class AudioFeatureExtractor(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val silenceThresholdDb: Float = -50f,
    private val bandCount: Int = BAND_COUNT
) {
    companion object {
        const val BAND_COUNT = 8
        private const val MIN_DB = -90f
        private fun ampToDb(amp: Float): Float =
            if (amp <= 1e-7f) MIN_DB else (20f * log10(amp)).coerceAtLeast(MIN_DB)
    }

    private var sampleCount = 0L
    private var sumSquares = 0.0
    private var peakAmplitude = 0f
    private var zeroCrossings = 0L
    private var hasLastSample = false
    private var lastSamplePositive = false

    private var silentFrames = 0
    private var activeFrames = 0
    private val rmsSeries = ArrayList<Float>()
    private val dbSeries = ArrayList<Float>()

    private val halfSize = fftSize / 2
    private val magAccum = DoubleArray(halfSize)
    private var spectralFrames = 0

    fun feed(frame: ShortArray) {
        if (frame.isEmpty()) return

        // amplitude + zero crossings
        for (s in frame) {
            val f = s.toFloat() / Short.MAX_VALUE
            sumSquares += f.toDouble() * f
            val a = abs(f)
            if (a > peakAmplitude) peakAmplitude = a
            val positive = f >= 0f
            if (hasLastSample && positive != lastSamplePositive) zeroCrossings++
            lastSamplePositive = positive
            hasLastSample = true
        }
        sampleCount += frame.size

        // per-frame timeseries + silence/activity
        val frameRms = FftProcessor.computeRms(frame)
        val frameDb = ampToDb(frameRms)
        rmsSeries.add(frameRms)
        dbSeries.add(frameDb)
        if (frameDb < silenceThresholdDb) silentFrames++ else activeFrames++

        // spectral (full frames only)
        if (frame.size == fftSize) {
            val mags = FftProcessor.computeMagnitudes(frame, fftSize)
            for (i in 0 until halfSize) magAccum[i] += mags[i].toDouble()
            spectralFrames++
        }
    }

    fun build(): AudioFeatures {
        val avgRms = if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount).toFloat() else 0f
        val avgDb = ampToDb(avgRms)
        val peakDb = ampToDb(peakAmplitude)
        val durationMs = if (sampleRate > 0) sampleCount * 1000 / sampleRate else 0L
        val zcr = if (sampleCount > 0) zeroCrossings.toFloat() / sampleCount else 0f
        val frameTotal = silentFrames + activeFrames
        val silenceRatio = if (frameTotal > 0) silentFrames.toFloat() / frameTotal else 0f
        val activeRatio = if (frameTotal > 0) activeFrames.toFloat() / frameTotal else 0f

        val avgMags = FloatArray(halfSize) {
            if (spectralFrames > 0) (magAccum[it] / spectralFrames).toFloat() else 0f
        }
        val dominantBin = avgMags.indices.maxByOrNull { avgMags[it] } ?: 0
        val dominantFreq = if (fftSize > 0) dominantBin.toFloat() * sampleRate / fftSize else 0f
        val spectralCentroid = computeCentroid(avgMags)
        val bandEnergy = computeBands(avgMags)

        return AudioFeatures(
            durationMs = durationMs,
            sampleCount = sampleCount,
            avgRms = avgRms,
            peakAmplitude = peakAmplitude,
            avgDb = avgDb,
            peakDb = peakDb,
            zeroCrossingRate = zcr,
            silenceRatio = silenceRatio,
            activeRatio = activeRatio,
            dominantFreq = dominantFreq,
            spectralCentroid = spectralCentroid,
            bandEnergy = bandEnergy,
            rmsSeries = rmsSeries.toFloatArray(),
            dbSeries = dbSeries.toFloatArray()
        )
    }

    private fun computeCentroid(mags: FloatArray): Float {
        var weighted = 0.0
        var total = 0.0
        for (i in mags.indices) {
            val freq = if (fftSize > 0) i.toDouble() * sampleRate / fftSize else 0.0
            weighted += freq * mags[i]
            total += mags[i]
        }
        return if (total > 0) (weighted / total).toFloat() else 0f
    }

    private fun computeBands(mags: FloatArray): FloatArray {
        val bands = FloatArray(bandCount)
        if (mags.isEmpty()) return bands
        for (i in mags.indices) {
            val band = (i * bandCount / mags.size).coerceIn(0, bandCount - 1)
            bands[band] += mags[i]
        }
        return bands
    }
}
