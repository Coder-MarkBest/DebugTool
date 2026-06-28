package com.debugtools.audiomon.view

import com.debugtools.audiomon.anomaly.AnomalyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioColorsTest {

    @Test
    fun `spectrogram color endpoints map to first and last LUT stop`() {
        assertEquals(0xFF15151F.toInt(), AudioColors.spectrogramColor(0f))
        assertEquals(0xFFFB7185.toInt(), AudioColors.spectrogramColor(1f))
    }

    @Test
    fun `spectrogram color clamps out-of-range input`() {
        assertEquals(AudioColors.spectrogramColor(0f), AudioColors.spectrogramColor(-5f))
        assertEquals(AudioColors.spectrogramColor(1f), AudioColors.spectrogramColor(9f))
    }

    @Test
    fun `mid level is fully opaque and between stops`() {
        val c = AudioColors.spectrogramColor(0.5f)
        assertEquals(0xFF, (c ushr 24) and 0xFF) // opaque
    }

    @Test
    fun `every anomaly type has a color`() {
        assertTrue(AnomalyType.entries.all { AudioColors.anomalyTypeColor(it) != 0 })
    }
}
