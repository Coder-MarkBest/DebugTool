package com.debugtools.audiomon.presenter

import android.util.Log
import com.debugtools.audiomon.anomaly.AnomalyEvent
import com.debugtools.audiomon.anomaly.AudioAnomalyDetector
import com.debugtools.audiomon.anomaly.StreamId
import com.debugtools.audiomon.audio.AudioRecorderWrapper
import com.debugtools.audiomon.audio.FftProcessor
import com.debugtools.audiomon.report.AudioReportData
import com.debugtools.audiomon.report.AudioReporter
import com.debugtools.audiomon.session.RecordingSessionController
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.sample
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * MVP Presenter for dual-stream recording with live scrolling visualization,
 * anomaly detection, and a max-duration auto-stop.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AudioPresenter(
    private val sampleRate: Int = AudioRecorderWrapper.DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = AudioRecorderWrapper.DEFAULT_FFT_SIZE,
    private val rootDir: File,
    private val silenceThresholdDb: Float = -50f,
    private val autoReport: Boolean = false,
    private val maxDurationSec: Int = 10,
    private val reporter: AudioReporter? = null
) {
    private companion object {
        const val TAG = "AudioPresenter"
        const val MIN_DB = -90f
    }

    private var view: AudioView? = null
    private var recorder: AudioRecorderWrapper? = null
    @Volatile private var controller: RecordingSessionController? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val reportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val aFrameFlow = MutableSharedFlow<ShortArray>(0, 8, BufferOverflow.DROP_OLDEST)

    private var recordJob: Job? = null
    private var bUiJob: Job? = null
    private var aUiJob: Job? = null
    private var durationJob: Job? = null
    private var isRecording = false
    private var lastSession: AudioReportData? = null
    private var startTimeMs = 0L

    private var bDetector = AudioAnomalyDetector(StreamId.B, silenceThresholdDb)
    private var aDetector = AudioAnomalyDetector(StreamId.A, silenceThresholdDb)
    private val bAnomalies = mutableListOf<AnomalyEvent>()
    private val aAnomalies = mutableListOf<AnomalyEvent>()

    val monitoring: Boolean get() = isRecording

    fun attach(view: AudioView) {
        this.view = view
        view.showStatus("点击按钮开始录制\n保存目录: ${rootDir.absolutePath}")
    }

    fun detach() {
        stopRecording()
        view = null
        scope.cancel()
    }

    /** Host pushes processed-audio PCM16 frames; ignored unless a session is active. */
    fun feedProcessedAudio(frame: ShortArray) {
        controller?.feedStreamA(frame)
        aFrameFlow.tryEmit(frame)
    }

    fun startRecording() {
        val v = view ?: return
        if (recordJob?.isActive == true) return

        val ctrl = RecordingSessionController(rootDir, sampleRate, fftSize, silenceThresholdDb)
        ctrl.start()
        controller = ctrl

        val rec = AudioRecorderWrapper(sampleRate, fftSize)
        recorder = rec
        val result = rec.start()
        if (result.isFailure) {
            v.showStatus("❌ ${result.exceptionOrNull()?.message}")
            ctrl.finish(); controller = null; recorder = null
            return
        }

        bDetector = AudioAnomalyDetector(StreamId.B, silenceThresholdDb)
        aDetector = AudioAnomalyDetector(StreamId.A, silenceThresholdDb)
        bAnomalies.clear(); aAnomalies.clear()
        startTimeMs = System.currentTimeMillis()
        isRecording = true
        v.clearLive()
        v.showMonitoringState(true)

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                rec.audioStream.collect { controller?.feedStreamB(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "stream B recording loop failed", e)
                withContext(Dispatchers.Main) { view?.showStatus("❌ 录制中断: ${e.message}") }
            }
        }

        bUiJob = scope.launch {
            rec.audioStream.sample(60).collect { processLiveFrame(StreamId.B, it, bDetector, bAnomalies) }
        }
        aUiJob = scope.launch {
            aFrameFlow.sample(60).collect { processLiveFrame(StreamId.A, it, aDetector, aAnomalies) }
        }

        durationJob = scope.launch {
            for (sec in 0 until maxDurationSec) {
                withContext(Dispatchers.Main) {
                    view?.showStatus("🎙️ 录制中 ${fmt(sec)} / ${fmt(maxDurationSec)}\n会话: ${ctrl.currentSessionDir?.name}")
                }
                delay(1000)
            }
            withContext(Dispatchers.Main) { if (isRecording) stopRecording() }
        }
    }

    private suspend fun processLiveFrame(
        stream: StreamId, frame: ShortArray,
        detector: AudioAnomalyDetector, sink: MutableList<AnomalyEvent>
    ) {
        val peak = peakOf(frame)
        val db = ampToDb(FftProcessor.computeRms(frame))
        val spectrum = if (frame.size == fftSize) FftProcessor.computeMagnitudes(frame, fftSize) else FloatArray(0)
        val timeMs = System.currentTimeMillis() - startTimeMs
        val events = detector.onFrame(timeMs, peak, db)
        withContext(Dispatchers.Main) {
            view?.pushLiveFrame(stream, db, spectrum)
            for (e in events) view?.showAnomaly(e)
        }
        if (events.isNotEmpty()) sink.addAll(events)
    }

    fun stopRecording() {
        durationJob?.cancel(); durationJob = null
        recordJob?.cancel(); bUiJob?.cancel(); aUiJob?.cancel()
        recordJob = null; bUiJob = null; aUiJob = null

        recorder?.stop(); recorder?.destroy(); recorder = null

        if (isRecording) {
            val elapsed = System.currentTimeMillis() - startTimeMs
            bAnomalies.addAll(bDetector.flush(elapsed))
            aAnomalies.addAll(aDetector.flush(elapsed))
        }

        val report = controller?.finish(bAnomalies.toList(), aAnomalies.toList())
        controller = null
        isRecording = false
        lastSession = report
        view?.showMonitoringState(false)

        if (report != null) {
            val aState = if (report.streamAWav != null) "A路+B路" else "仅B路"
            val sizeKb = (report.streamBWav?.length() ?: 0L) / 1024
            val anomCount = bAnomalies.size + aAnomalies.size
            view?.showStatus("✅ 会话完成: ${report.sessionId}")
            view?.showLastSession(report.sessionId, "$aState | B路 ${sizeKb}KB | 异常 $anomCount", reporter != null)
            if (autoReport && reporter != null) dispatchReport(report)
        } else {
            view?.showStatus("已停止")
        }
    }

    fun reportLastSession() {
        val report = lastSession ?: return
        if (reporter == null) { view?.showStatus("⚠️ 未配置上报接口"); return }
        dispatchReport(report)
    }

    private fun dispatchReport(report: AudioReportData) {
        reportScope.launch {
            val ok = runCatching { reporter?.report(report) }.isSuccess
            withContext(Dispatchers.Main) {
                view?.showStatus(if (ok) "📤 已上报: ${report.sessionId}" else "❌ 上报失败: ${report.sessionId}")
            }
        }
    }

    fun toggleMonitoring() { if (isRecording) stopRecording() else startRecording() }

    private fun peakOf(frame: ShortArray): Float {
        var m = 0
        for (s in frame) {
            val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(s.toInt())
            if (a > m) m = a
        }
        return m.toFloat() / Short.MAX_VALUE
    }

    private fun ampToDb(amp: Float): Float =
        if (amp <= 1e-7f) MIN_DB else (20f * log10(amp)).coerceAtLeast(MIN_DB)

    private fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
}
