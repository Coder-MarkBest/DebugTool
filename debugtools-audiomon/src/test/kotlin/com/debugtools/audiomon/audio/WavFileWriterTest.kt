package com.debugtools.audiomon.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ascii(bytes: ByteArray, offset: Int, len: Int): String =
        String(bytes, offset, len, Charsets.US_ASCII)

    private fun le(bytes: ByteArray, offset: Int, len: Int): Int =
        ByteBuffer.wrap(bytes, offset, len).order(ByteOrder.LITTLE_ENDIAN).let {
            if (len == 2) it.short.toInt() and 0xFFFF else it.int
        }

    @Test
    fun `open writes a 44 byte placeholder header`() {
        val file = tmp.newFile("placeholder.wav")
        val writer = WavFileWriter(file, sampleRate = 16000)
        assertTrue(writer.open().isSuccess)
        writer.close()
        // Closed with no data -> header stays the 44-byte placeholder.
        assertEquals(44L, file.length())
    }

    @Test
    fun `writeSamples appends two bytes per sample`() {
        val file = tmp.newFile("data.wav")
        val writer = WavFileWriter(file, sampleRate = 16000)
        writer.open()
        writer.writeSamples(ShortArray(100))
        writer.close()
        // 44-byte header + 100 samples * 2 bytes.
        assertEquals(44L + 200L, file.length())
    }

    @Test
    fun `close patches a valid WAV header`() {
        val file = tmp.newFile("valid.wav")
        val sampleRate = 16000
        val sampleCount = 50
        val writer = WavFileWriter(file, sampleRate = sampleRate)
        writer.open()
        writer.writeSamples(ShortArray(sampleCount) { it.toShort() })
        writer.close()

        val h = file.readBytes()
        val dataSize = sampleCount * 2

        assertEquals("RIFF", ascii(h, 0, 4))
        assertEquals(36 + dataSize, le(h, 4, 4))      // ChunkSize
        assertEquals("WAVE", ascii(h, 8, 4))
        assertEquals("fmt ", ascii(h, 12, 4))
        assertEquals(16, le(h, 16, 4))                // PCM fmt chunk size
        assertEquals(1, le(h, 20, 2))                 // AudioFormat = PCM
        assertEquals(1, le(h, 22, 2))                 // channels (mono default)
        assertEquals(sampleRate, le(h, 24, 4))        // sample rate
        assertEquals(sampleRate * 1 * 16 / 8, le(h, 28, 4)) // byte rate
        assertEquals(1 * 16 / 8, le(h, 32, 2))        // block align
        assertEquals(16, le(h, 34, 2))                // bits per sample
        assertEquals("data", ascii(h, 36, 4))
        assertEquals(dataSize, le(h, 40, 4))          // data size
    }

    @Test
    fun `targetFile reflects open state`() {
        val file = tmp.newFile("state.wav")
        val writer = WavFileWriter(file, sampleRate = 16000)
        assertNull(writer.targetFile)
        writer.open()
        assertEquals(file, writer.targetFile)
        writer.close()
        assertNull(writer.targetFile)
    }

    @Test
    fun `close is idempotent`() {
        val file = tmp.newFile("idem.wav")
        val writer = WavFileWriter(file, sampleRate = 16000)
        writer.open()
        writer.writeSamples(ShortArray(10))
        writer.close()
        writer.close() // second call must not throw
        assertEquals(44L + 20L, file.length())
    }

    @Test
    fun `open creates missing parent directories`() {
        val nested = File(tmp.root, "a/b/c/nested.wav")
        val writer = WavFileWriter(nested, sampleRate = 16000)
        assertTrue(writer.open().isSuccess)
        writer.close()
        assertTrue(nested.exists())
    }
}
