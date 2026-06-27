package com.debugtools.audiomon.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes PCM16 mono audio to a WAV file.
 *
 * Usage:
 * ```
 * val writer = WavFileWriter(file, sampleRate = 16000)
 * writer.open()
 * writer.writeSamples(shortArray)  // call repeatedly
 * writer.close()                   // finalizes WAV header
 * ```
 *
 * The WAV header is written as a placeholder on [open] and patched with
 * the correct data size on [close].
 */
class WavFileWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val bitsPerSample: Int = 16
) {

    private var outputStream: FileOutputStream? = null
    private var totalDataBytes: Long = 0
    private var isOpen = false

    /** @return the target file, or null if not open. */
    val targetFile: File? get() = if (isOpen) file else null

    /**
     * Create the file and write a placeholder WAV header (44 bytes).
     * @return [Result.success] or [Result.failure] on IO error.
     */
    fun open(): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        outputStream = FileOutputStream(file).also { fos ->
            // Write 44-byte placeholder header; will be patched on close()
            fos.write(ByteArray(44))
        }
        totalDataBytes = 0
        isOpen = true
    }.onFailure { close() }

    /**
     * Append PCM16 samples to the file.
     */
    fun writeSamples(samples: ShortArray) {
        val os = outputStream ?: return
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        val bytes = buf.array()
        os.write(bytes)
        totalDataBytes += bytes.size
    }

    /**
     * Finalize the WAV header with correct sizes and close the stream.
     * Safe to call multiple times.
     */
    fun close() {
        if (!isOpen) return
        isOpen = false

        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Exception) { /* ignore */ }
        outputStream = null

        // Patch WAV header
        if (totalDataBytes > 0) {
            try {
                patchHeader()
            } catch (_: Exception) { /* best effort */ }
        }
    }

    private fun patchHeader() {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = totalDataBytes.toInt()
        val chunkSize = 36 + dataSize

        RandomAccessFile(file, "rw").use { raf ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF chunk
            header.put('R'.code.toByte())
            header.put('I'.code.toByte())
            header.put('F'.code.toByte())
            header.put('F'.code.toByte())
            header.putInt(chunkSize)              // ChunkSize
            header.put('W'.code.toByte())
            header.put('A'.code.toByte())
            header.put('V'.code.toByte())
            header.put('E'.code.toByte())

            // fmt sub-chunk
            header.put('f'.code.toByte())
            header.put('m'.code.toByte())
            header.put('t'.code.toByte())
            header.put(' '.code.toByte())
            header.putInt(16)                     // Sub-chunk size (PCM = 16)
            header.putShort(1)                    // AudioFormat (PCM = 1)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())

            // data sub-chunk
            header.put('d'.code.toByte())
            header.put('a'.code.toByte())
            header.put('t'.code.toByte())
            header.put('a'.code.toByte())
            header.putInt(dataSize)

            raf.seek(0)
            raf.write(header.array())
        }
    }
}
