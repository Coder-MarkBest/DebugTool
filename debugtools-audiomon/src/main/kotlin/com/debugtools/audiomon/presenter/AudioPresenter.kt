package com.debugtools.audiomon.presenter

import com.debugtools.audiomon.audio.AudioRecorderWrapper
import com.debugtools.audiomon.audio.FftProcessor
import com.debugtools.audiomon.report.AudioReportData
import com.debugtools.audiomon.report.AudioReporter
import com.debugtools.audiomon.session.RecordingSessionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import java.io.File

/**
 * MVP Presenter for dual-stream audio recording.
 *
 * Stream B (mic) is captured by [AudioRecorderWrapper] and fed to both the live
 * oscilloscope/FFT view and the [RecordingSessionController]. Stream A (the
 * host's processed audio) arrives via [feedProcessedAudio]. On stop, the
 * controller writes both WAVs + features + session.json, and the session is
 * optionally auto-reported.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AudioPresenter(
    private val sampleRate: Int = AudioRecorderWrapper.DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = AudioRecorderWrapper.DEFAULT_FFT_SIZE,
    private val rootDir: File,
    private val silenceThresholdDb: Float = -50f,
    private val autoReport: Boolean = false,
    private val reporter: AudioReporter? = null
) {
    private var view: AudioView? = null
    private var recorder: AudioRecorderWrapper? = null
    private var controller: RecordingSessionController? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null
    private var isRecording = false
    private var lastSession: AudioReportData? = null

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
    }

    fun startRecording() {
        val v = view ?: return
        if (collectJob?.isActive == true) return

        val ctrl = RecordingSessionController(rootDir, sampleRate, fftSize, silenceThresholdDb)
        ctrl.start()
        controller = ctrl

        val rec = AudioRecorderWrapper(sampleRate, fftSize, saveDir = null, saveEnabled = false)
        recorder = rec
        val result = rec.start()
        if (result.isFailure) {
            v.showStatus("❌ ${result.exceptionOrNull()?.message}")
            ctrl.finish()
            controller = null
            recorder = null
            return
        }

        isRecording = true
        v.showMonitoringState(true)
        v.showStatus("🎙️ 录制中 (${sampleRate}Hz)\n会话: ${ctrl.currentSessionDir?.name}")

        collectJob = scope.launch {
            rec.audioStream
                .sample(60) // ~16 FPS UI updates; controller still receives every frame
                .collect { pcmBuffer ->
                    controller?.feedStreamB(pcmBuffer)

                    val floatSamples = FloatArray(pcmBuffer.size) {
                        pcmBuffer[it].toFloat() / Short.MAX_VALUE
                    }
                    val rms = FftProcessor.computeRms(pcmBuffer)
                    val spectrum = if (pcmBuffer.size == fftSize) {
                        FftProcessor.computeMagnitudes(pcmBuffer, fftSize)
                    } else null

                    withContext(Dispatchers.Main) {
                        view?.showWaveform(floatSamples, rms)
                        if (spectrum != null) view?.showSpectrum(spectrum)
                    }
                }
        }
    }

    fun stopRecording() {
        collectJob?.cancel()
        collectJob = null

        recorder?.stop()
        recorder?.destroy()
        recorder = null

        val report = controller?.finish()
        controller = null
        isRecording = false
        lastSession = report
        view?.showMonitoringState(false)

        if (report != null) {
            val aState = if (report.streamAWav != null) "A路+B路" else "仅B路"
            val sizeKb = (report.streamBWav?.length() ?: 0L) / 1024
            val summary = "$aState | B路 ${sizeKb}KB"
            view?.showStatus("✅ 会话完成: ${report.sessionId}")
            view?.showLastSession(report.sessionId, summary, reporter != null)
            if (autoReport && reporter != null) {
                dispatchReport(report)
            }
        } else {
            view?.showStatus("已停止")
        }
    }

    /** Manually trigger upload of the most recent session (called from the UI button). */
    fun reportLastSession() {
        val report = lastSession ?: return
        if (reporter == null) {
            view?.showStatus("⚠️ 未配置上报接口")
            return
        }
        dispatchReport(report)
    }

    private fun dispatchReport(report: AudioReportData) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { reporter?.report(report) }.isSuccess
            withContext(Dispatchers.Main) {
                view?.showStatus(if (ok) "📤 已上报: ${report.sessionId}" else "❌ 上报失败: ${report.sessionId}")
            }
        }
    }

    fun toggleMonitoring() {
        if (isRecording) stopRecording() else startRecording()
    }
}
