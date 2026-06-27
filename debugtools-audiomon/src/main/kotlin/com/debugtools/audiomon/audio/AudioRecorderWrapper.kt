package com.debugtools.audiomon.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wraps [AudioRecord] to emit PCM16 buffers as a hot [SharedFlow],
 * with optional WAV file recording.
 *
 * Backpressure is handled via [BufferOverflow.DROP_OLDEST] — the audio thread
 * never blocks; stale frames are simply discarded when the consumer can't keep up.
 *
 * @param sampleRate Audio sample rate in Hz.
 * @param fftSize FFT window size (number of samples per read).
 * @param saveDir Directory for WAV files. Null = no file recording.
 * @param saveEnabled Whether file recording is active.
 */
@SuppressLint("MissingPermission")
class AudioRecorderWrapper(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = DEFAULT_FFT_SIZE,
    private val saveDir: File? = null,
    private val saveEnabled: Boolean = false
) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_FFT_SIZE = 1024

        private val FILE_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private val _audioStream = MutableSharedFlow<ShortArray>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioStream: SharedFlow<ShortArray> = _audioStream.asSharedFlow()

    private var audioRecord: AudioRecord? = null
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wavWriter: WavFileWriter? = null

    /** The file currently being written to, or null. */
    val currentFile: File? get() = wavWriter?.targetFile

    /**
     * Initialize and start recording.
     * @return [Result.success] on success, [Result.failure] with the cause on error.
     */
    fun start(): Result<Unit> = runCatching {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "Invalid audio config: minBufferSize=$minBuf" }

        // *2 for short→bytes, *2 for headroom
        val bufferSize = maxOf(minBuf, fftSize * 4)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        ).also { record ->
            check(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialize (state=${record.state})"
            }
            record.startRecording()
        }

        // Start WAV writer if save is enabled
        if (saveEnabled && saveDir != null) {
            val fileName = "audiomon_${FILE_DATE_FORMAT.format(Date())}.wav"
            val file = File(saveDir, fileName)
            wavWriter = WavFileWriter(file, sampleRate).also { writer ->
                val result = writer.open()
                if (result.isFailure) {
                    wavWriter = null // don't fail the whole recording, just skip saving
                }
            }
        }

        readJob = scope.launch {
            val buffer = ShortArray(fftSize)
            while (currentCoroutineContext().isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val frame = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    _audioStream.tryEmit(frame)
                    wavWriter?.writeSamples(frame)
                }
            }
        }
    }.onFailure { stop() }

    fun stop(): File? {
        readJob?.cancel()
        readJob = null

        // Close WAV writer and get the saved file
        val savedFile = wavWriter?.targetFile
        wavWriter?.close()
        wavWriter = null

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) { /* already stopped */ }
        audioRecord?.release()
        audioRecord = null

        return savedFile
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
