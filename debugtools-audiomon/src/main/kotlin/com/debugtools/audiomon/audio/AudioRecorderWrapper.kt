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

/**
 * Wraps [AudioRecord] to emit PCM16 buffers as a hot [SharedFlow].
 *
 * Backpressure is handled via [BufferOverflow.DROP_OLDEST] — the audio thread
 * never blocks; stale frames are simply discarded when the consumer can't keep up.
 *
 * WAV persistence is intentionally not handled here: callers that need to record
 * to disk consume [audioStream] and write the frames themselves (see
 * `RecordingSessionController`).
 *
 * @param sampleRate Audio sample rate in Hz.
 * @param fftSize FFT window size (number of samples per read).
 */
@SuppressLint("MissingPermission")
class AudioRecorderWrapper(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = DEFAULT_FFT_SIZE
) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16000
        const val DEFAULT_FFT_SIZE = 1024
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

        readJob = scope.launch {
            val buffer = ShortArray(fftSize)
            while (currentCoroutineContext().isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val frame = if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read)
                    _audioStream.tryEmit(frame)
                }
            }
        }
    }.onFailure { stop() }

    fun stop() {
        readJob?.cancel()
        readJob = null

        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) { /* already stopped */ }
        audioRecord?.release()
        audioRecord = null
    }

    fun destroy() {
        stop()
        scope.cancel()
    }
}
