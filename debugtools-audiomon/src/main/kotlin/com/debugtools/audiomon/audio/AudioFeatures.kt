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
        put("avgRms", avgRms.toDouble())
        put("peakAmplitude", peakAmplitude.toDouble())
        put("avgDb", avgDb.toDouble())
        put("peakDb", peakDb.toDouble())
        put("zeroCrossingRate", zeroCrossingRate.toDouble())
        put("silenceRatio", silenceRatio.toDouble())
        put("activeRatio", activeRatio.toDouble())
        put("dominantFreq", dominantFreq.toDouble())
        put("spectralCentroid", spectralCentroid.toDouble())
        put("bandEnergy", bandEnergy.toJsonArray())
        put("rmsSeries", rmsSeries.toJsonArray())
        put("dbSeries", dbSeries.toJsonArray())
    }

    fun summaryJson(): JSONObject = JSONObject().apply {
        put("durationMs", durationMs)
        put("avgDb", avgDb.toDouble())
        put("peakDb", peakDb.toDouble())
        put("activeRatio", activeRatio.toDouble())
    }

    private fun FloatArray.toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (v in this) arr.put(v.toDouble())
        return arr
    }

    // data class with array fields: equals/hashCode unused by app logic; omit override.
}
