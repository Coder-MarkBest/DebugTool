package com.debugtools.audiomon.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class RecordingSessionControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sampleRate = 16000
    private val fftSize = 1024

    private fun sine(size: Int, cycles: Int, amp: Double = 0.5): ShortArray =
        ShortArray(size) { i ->
            (Short.MAX_VALUE * amp * sin(2.0 * PI * cycles * i / size)).roundToInt().toShort()
        }

    private fun controller(root: java.io.File) = RecordingSessionController(
        rootDir = root,
        sampleRate = sampleRate,
        fftSize = fftSize,
        silenceThresholdDb = -50f,
        clock = { 1_000L },
        sessionIdProvider = { "testsession" }
    )

    @Test
    fun `finish without start returns null`() {
        assertNull(controller(tmp.root).finish())
    }

    @Test
    fun `both streams produce wav and feature files and session json`() {
        val c = controller(tmp.root)
        c.start()
        repeat(8) {
            c.feedStreamB(sine(fftSize, 64, 0.5))
            c.feedStreamA(sine(fftSize, 64, 0.3))
        }
        val report = c.finish()
        assertNotNull(report)
        report!!
        assertEquals("testsession", report.sessionId)
        assertTrue(report.streamBWav!!.exists() && report.streamBWav!!.length() > 44)
        assertTrue(report.streamAWav!!.exists() && report.streamAWav!!.length() > 44)
        assertTrue(report.streamBFeatures!!.exists())
        assertTrue(report.streamAFeatures!!.exists())
        assertTrue(report.metadata.exists())

        val json = JSONObject(report.metadata.readText())
        assertEquals("testsession", json.getString("sessionId"))
        assertEquals(sampleRate, json.getInt("sampleRate"))
        val streams = json.getJSONObject("streams")
        assertTrue(streams.getJSONObject("streamB").getBoolean("present"))
        assertTrue(streams.getJSONObject("streamA").getBoolean("present"))
        assertTrue(streams.getJSONObject("streamB").getJSONObject("summary").has("avgDb"))
    }

    @Test
    fun `stream A absent when host never feeds it`() {
        val c = controller(tmp.root)
        c.start()
        repeat(4) { c.feedStreamB(sine(fftSize, 64, 0.5)) }
        val report = c.finish()!!
        assertNull(report.streamAWav)
        assertNull(report.streamAFeatures)
        assertFalse(java.io.File(report.sessionDir, "streamA.wav").exists())

        val streams = JSONObject(report.metadata.readText()).getJSONObject("streams")
        assertFalse(streams.getJSONObject("streamA").getBoolean("present"))
    }
}
