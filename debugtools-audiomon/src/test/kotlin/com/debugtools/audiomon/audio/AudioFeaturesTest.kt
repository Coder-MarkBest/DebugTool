package com.debugtools.audiomon.audio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFeaturesTest {

    private fun sample() = AudioFeatures(
        durationMs = 1000L,
        sampleCount = 16000L,
        avgRms = 0.25f,
        peakAmplitude = 0.9f,
        avgDb = -12.0f,
        peakDb = -0.9f,
        zeroCrossingRate = 0.05f,
        silenceRatio = 0.3f,
        activeRatio = 0.7f,
        dominantFreq = 440.0f,
        spectralCentroid = 1200.0f,
        bandEnergy = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.3f, 0.2f, 0.1f, 0.05f),
        rmsSeries = floatArrayOf(0.2f, 0.3f, 0.25f),
        dbSeries = floatArrayOf(-14f, -10f, -12f)
    )

    @Test
    fun `toJson contains all feature groups`() {
        val json = sample().toJson()
        assertEquals(1000L, json.getLong("durationMs"))
        assertEquals(440.0, json.getDouble("dominantFreq"), 1e-3)
        assertEquals(8, json.getJSONArray("bandEnergy").length())
        assertEquals(3, json.getJSONArray("rmsSeries").length())
        assertEquals(0.7, json.getDouble("activeRatio"), 1e-3)
    }

    @Test
    fun `summaryJson exposes only overview fields`() {
        val s = sample().summaryJson()
        assertEquals(1000L, s.getLong("durationMs"))
        assertEquals(-12.0, s.getDouble("avgDb"), 1e-3)
        assertEquals(-0.9, s.getDouble("peakDb"), 1e-3)
        assertEquals(0.7, s.getDouble("activeRatio"), 1e-3)
        assertTrue(!s.has("rmsSeries"))
    }
}
