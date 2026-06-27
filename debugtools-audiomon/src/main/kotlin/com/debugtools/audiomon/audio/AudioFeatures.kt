package com.debugtools.audiomon.audio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Aggregated numerical features for one recorded audio stream.
 *
 * Pure data holder. Computed by [AudioFeatureExtractor]; serialized to a
 * `*.features.json` file by [toJson]. [summaryJson] returns the small subset
 * embedded into `session.json` for at-a-glance display.
 */
data class AudioFeatures(
    // basic amplitude
    val durationMs: Long,
    val sampleCount: Long,
    val avgRms: Float,
    val peakAmplitude: Float,
    val avgDb: Float,
    val peakDb: Float,
    // silence / activity
    val zeroCrossingRate: Float,
    val silenceRatio: Float,
    val activeRatio: Float,
    // spectral
    val dominantFreq: Float,
    val spectralCentroid: Float,
    val bandEnergy: FloatArray,
    // per-frame timeseries
    val rmsSeries: FloatArray,
    val dbSeries: FloatArray
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("durationMs", durationMs)
        put("sampleCount", sampleCount)
        put("avgRms", avgRms.toJsonDouble())
        put("peakAmplitude", peakAmplitude.toJsonDouble())
        put("avgDb", avgDb.toJsonDouble())
        put("peakDb", peakDb.toJsonDouble())
        put("zeroCrossingRate", zeroCrossingRate.toJsonDouble())
        put("silenceRatio", silenceRatio.toJsonDouble())
        put("activeRatio", activeRatio.toJsonDouble())
        put("dominantFreq", dominantFreq.toJsonDouble())
        put("spectralCentroid", spectralCentroid.toJsonDouble())
        put("bandEnergy", bandEnergy.toJsonArray())
        put("rmsSeries", rmsSeries.toJsonArray())
        put("dbSeries", dbSeries.toJsonArray())
    }

    fun summaryJson(): JSONObject = JSONObject().apply {
        put("durationMs", durationMs)
        put("avgDb", avgDb.toJsonDouble())
        put("peakDb", peakDb.toJsonDouble())
        put("activeRatio", activeRatio.toJsonDouble())
    }

    private fun Float.toJsonDouble(): Double = Math.round(this.toDouble() * 100000.0) / 100000.0

    private fun FloatArray.toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (v in this) arr.put(v.toJsonDouble())
        return arr
    }

    // data class with array fields: equals/hashCode unused by app logic; omit override.
}
