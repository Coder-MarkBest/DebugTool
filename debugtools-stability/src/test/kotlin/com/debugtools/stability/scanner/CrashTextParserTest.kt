package com.debugtools.stability.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashTextParserTest {
    @Test
    fun `extracts java crash process`() {
        assertEquals("com.voice.app", CrashTextParser.extractProcessName("Process: com.voice.app\nPID: 123"))
    }

    @Test
    fun `extracts anr cmd line process`() {
        assertEquals("com.voice.asr", CrashTextParser.extractProcessName("Cmd line: com.voice.asr\nBuild fingerprint: x"))
    }

    @Test
    fun `extracts native tombstone process`() {
        assertEquals("com.voice.native", CrashTextParser.extractProcessName(">>> com.voice.native <<<\nbacktrace:"))
    }

    @Test
    fun `extracts pid`() {
        assertEquals(123, CrashTextParser.extractPid("PID: 123"))
    }

    @Test
    fun `returns null when process missing`() {
        assertNull(CrashTextParser.extractProcessName("no process here"))
    }
}
