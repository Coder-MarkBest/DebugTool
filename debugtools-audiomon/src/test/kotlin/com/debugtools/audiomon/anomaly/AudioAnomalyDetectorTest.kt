package com.debugtools.audiomon.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnomalyDetectorTest {

    private fun detector() = AudioAnomalyDetector(StreamId.B, silenceThresholdDb = -50f)

    @Test
    fun `consecutive clipping frames collapse into one episode`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        out += d.onFrame(0, peak = 1.0f, db = -3f)
        out += d.onFrame(64, peak = 1.0f, db = -3f)
        out += d.onFrame(128, peak = 1.0f, db = -3f)
        out += d.onFrame(192, peak = 1.0f, db = -3f)
        out += d.onFrame(256, peak = 0.1f, db = -20f)
        val clips = out.filter { it.type == AnomalyType.CLIPPING }
        assertEquals(1, clips.size)
        assertEquals(0L, clips[0].timeMs)
    }

    @Test
    fun `energy jump fires once per transition not per sustained frame`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        out += d.onFrame(0, 0.1f, -40f)
        out += d.onFrame(64, 0.1f, -40f)
        out += d.onFrame(128, 0.1f, -10f)  // +30dB jump
        out += d.onFrame(192, 0.1f, -10f)  // steady, no repeat
        out += d.onFrame(256, 0.1f, -40f)  // -30dB jump
        assertEquals(2, out.count { it.type == AnomalyType.ENERGY_JUMP })
    }

    @Test
    fun `sustained true silence over 1s is a dropout`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(7) { out += d.onFrame(t, 0.0f, -70f); t += 200 } // 0..1200ms quiet, avg -70
        out += d.onFrame(t, 0.1f, -20f) // closes the quiet run
        val ev = out.filter { it.type == AnomalyType.SILENCE_DROPOUT }
        assertEquals(1, ev.size)
        assertTrue(out.none { it.type == AnomalyType.HIGH_NOISE_FLOOR })
    }

    @Test
    fun `short quiet gap under 1s is not a dropout`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(3) { out += d.onFrame(t, 0.0f, -70f); t += 200 } // 0..400ms
        out += d.onFrame(t, 0.1f, -20f)
        assertTrue(out.none { it.type == AnomalyType.SILENCE_DROPOUT })
    }

    @Test
    fun `noisy quiet segment is high noise floor not dropout`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(4) { out += d.onFrame(t, 0.1f, -55f); t += 200 } // 0..600ms, below -50 but avg -55 > -60
        out += d.onFrame(t, 0.1f, -20f)
        assertEquals(1, out.count { it.type == AnomalyType.HIGH_NOISE_FLOOR })
        assertTrue(out.none { it.type == AnomalyType.SILENCE_DROPOUT })
    }

    @Test
    fun `flush closes an open quiet segment at end of recording`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(7) { out += d.onFrame(t, 0.0f, -70f); t += 200 } // open quiet run, never closed
        out += d.flush(1300)
        assertEquals(1, out.count { it.type == AnomalyType.SILENCE_DROPOUT })
    }
}
