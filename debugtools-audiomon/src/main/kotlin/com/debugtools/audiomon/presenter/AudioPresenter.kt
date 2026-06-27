package com.debugtools.audiomon.presenter

import com.debugtools.audiomon.audio.AudioRecorderWrapper
import com.debugtools.audiomon.audio.FftProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import java.io.File

/**
 * MVP Presenter for audio monitoring.
 *
 * Collects raw PCM data from [AudioRecorderWrapper], sends the raw sample
 * window and RMS to the view for oscilloscope display, and computes FFT
 * spectrum. Optionally records audio to a WAV file.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AudioPresenter(
    private val sampleRate: Int = AudioRecorderWrapper.DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = AudioRecorderWrapper.DEFAULT_FFT_SIZE,
    private val saveDir: File? = null,
    private val saveEnabled: Boolean = false
) {

    private var view: AudioView? = null
    private var recorder: AudioRecorderWrapper? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null
    private var isMonitoring = false

    val monitoring: Boolean get() = isMonitoring

    fun attach(view: AudioView) {
        this.view = view
        val saveInfo = if (saveEnabled && saveDir != null) " | 保存至: ${saveDir.absolutePath}" else ""
        view.showStatus("点击按钮开始录音$saveInfo")
    }

    fun detach() {
        stopMonitoring()
        view = null
        scope.cancel()
    }

    fun startMonitoring() {
        val v = view ?: return
        if (collectJob?.isActive == true) return

        val rec = AudioRecorderWrapper(sampleRate, fftSize, saveDir, saveEnabled)
        recorder = rec

        val result = rec.start()
        if (result.isFailure) {
            v.showStatus("❌ ${result.exceptionOrNull()?.message}")
            recorder = null
            return
        }

        isMonitoring = true
        v.showMonitoringState(true)

        val statusBuild = buildString {
            append("🎙️ 录音中 (${sampleRate}Hz)")
            if (saveEnabled && rec.currentFile != null) {
                append("\n💾 保存至: ${rec.currentFile!!.name}")
            }
        }
        v.showStatus(statusBuild)

        collectJob = scope.launch {
            rec.audioStream
                .sample(60) // ~16 FPS UI updates
                .collect { pcmBuffer ->
                    // Convert to normalized float samples (-1..1)
                    val floatSamples = FloatArray(pcmBuffer.size) {
                        pcmBuffer[it].toFloat() / Short.MAX_VALUE
                    }

                    // RMS amplitude
                    val rms = FftProcessor.computeRms(pcmBuffer)

                    // FFT spectrum (only when buffer matches fftSize exactly)
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

    fun stopMonitoring() {
        collectJob?.cancel()
        collectJob = null

        val savedFile = recorder?.stop()
        recorder?.destroy()
        recorder = null
        isMonitoring = false
        view?.showMonitoringState(false)

        val statusBuild = buildString {
            append("已停止")
            if (savedFile != null && savedFile.exists()) {
                append("\n✅ 已保存: ${savedFile.name} (${savedFile.length() / 1024}KB)")
            }
        }
        view?.showStatus(statusBuild)
    }

    fun toggleMonitoring() {
        if (isMonitoring) stopMonitoring() else startMonitoring()
    }
}
