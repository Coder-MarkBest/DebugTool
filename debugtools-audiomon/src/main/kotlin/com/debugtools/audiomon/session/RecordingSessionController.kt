package com.debugtools.audiomon.session

import com.debugtools.audiomon.audio.AudioFeatureExtractor
import com.debugtools.audiomon.audio.AudioFeatures
import com.debugtools.audiomon.audio.WavFileWriter
import com.debugtools.audiomon.report.AudioReportData
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Orchestrates one recording session: two parallel PCM16 streams written to
 * WAV plus per-stream [AudioFeatureExtractor], finalized into a session
 * directory with a `session.json` and returned as [AudioReportData].
 *
 * Stream B (DebugTool mic capture) is fed from the capture coroutine; stream A
 * (host's processed audio) is pushed from the host's audio thread and its
 * writer/extractor are created lazily on the first frame. All public methods
 * are synchronized to guard the lazily-created stream-A state across threads.
 *
 * [clock] and [sessionIdProvider] are injectable for deterministic tests.
 */
class RecordingSessionController(
    private val rootDir: File,
    private val sampleRate: Int,
    private val fftSize: Int,
    private val silenceThresholdDb: Float,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sessionIdProvider: () -> String = { defaultSessionId() }
) {
    companion object {
        private val ID_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private const val SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        private fun defaultSessionId(): String {
            val ts = ID_DATE_FORMAT.format(Date())
            val suffix = (1..4).map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }.joinToString("")
            return "${ts}_$suffix"
        }
    }

    private var sessionId: String? = null
    private var sessionDir: File? = null
    private var startTime = 0L

    private var bWriter: WavFileWriter? = null
    private var bExtractor: AudioFeatureExtractor? = null
    private var aWriter: WavFileWriter? = null
    private var aExtractor: AudioFeatureExtractor? = null

    val currentSessionDir: File? @Synchronized get() = sessionDir

    @Synchronized
    fun start() {
        val id = sessionIdProvider()
        val dir = File(rootDir, id).apply { mkdirs() }
        sessionId = id
        sessionDir = dir
        startTime = clock()

        bWriter = WavFileWriter(File(dir, "streamB.wav"), sampleRate).also { it.open() }
        bExtractor = newExtractor()
    }

    @Synchronized
    fun feedStreamB(frame: ShortArray) {
        bWriter?.writeSamples(frame)
        bExtractor?.feed(frame)
    }

    @Synchronized
    fun feedStreamA(frame: ShortArray) {
        val dir = sessionDir ?: return
        if (aWriter == null) {
            aWriter = WavFileWriter(File(dir, "streamA.wav"), sampleRate).also { it.open() }
            aExtractor = newExtractor()
        }
        aWriter?.writeSamples(frame)
        aExtractor?.feed(frame)
    }

    /** Finalize files and return the session artifacts, or null if not started. */
    @Synchronized
    fun finish(): AudioReportData? {
        val id = sessionId ?: return null
        val dir = sessionDir ?: return null
        val endTime = clock()

        bWriter?.close()
        aWriter?.close()

        val bFeatures = bExtractor?.build()
        val aFeatures = aExtractor?.build()

        val bFeaturesFile = bFeatures?.let { writeFeatures(dir, "streamB.features.json", it) }
        val aFeaturesFile = aFeatures?.let { writeFeatures(dir, "streamA.features.json", it) }

        val aPresent = aExtractor != null
        val metadata = writeSessionJson(dir, id, endTime, bFeatures, aFeatures, aPresent)

        val report = AudioReportData(
            sessionId = id,
            sessionDir = dir,
            streamBWav = File(dir, "streamB.wav").takeIf { it.exists() },
            streamBFeatures = bFeaturesFile,
            streamAWav = if (aPresent) File(dir, "streamA.wav").takeIf { it.exists() } else null,
            streamAFeatures = aFeaturesFile,
            metadata = metadata
        )

        reset()
        return report
    }

    private fun newExtractor() =
        AudioFeatureExtractor(sampleRate, fftSize, silenceThresholdDb)

    private fun writeFeatures(dir: File, name: String, features: AudioFeatures): File {
        val file = File(dir, name)
        file.writeText(features.toJson().toString())
        return file
    }

    private fun writeSessionJson(
        dir: File,
        id: String,
        endTime: Long,
        bFeatures: AudioFeatures?,
        aFeatures: AudioFeatures?,
        aPresent: Boolean
    ): File {
        val streams = JSONObject().apply {
            put("streamB", streamNode(present = true, wav = "streamB.wav",
                features = "streamB.features.json", f = bFeatures))
            put("streamA", streamNode(present = aPresent,
                wav = if (aPresent) "streamA.wav" else JSONObject.NULL,
                features = if (aPresent) "streamA.features.json" else JSONObject.NULL,
                f = aFeatures))
        }
        val root = JSONObject().apply {
            put("sessionId", id)
            put("startTime", startTime)
            put("endTime", endTime)
            put("durationMs", endTime - startTime)
            put("sampleRate", sampleRate)
            put("streams", streams)
        }
        val file = File(dir, "session.json")
        file.writeText(root.toString())
        return file
    }

    private fun streamNode(present: Boolean, wav: Any?, features: Any?, f: AudioFeatures?): JSONObject =
        JSONObject().apply {
            put("present", present)
            put("wav", wav ?: JSONObject.NULL)
            put("features", features ?: JSONObject.NULL)
            put("summary", f?.summaryJson() ?: JSONObject.NULL)
        }

    private fun reset() {
        sessionId = null
        sessionDir = null
        bWriter = null; bExtractor = null
        aWriter = null; aExtractor = null
    }
}
