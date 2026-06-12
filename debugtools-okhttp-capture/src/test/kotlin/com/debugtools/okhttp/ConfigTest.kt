package com.debugtools.okhttp

import org.junit.Assert.*
import org.junit.Test

class ConfigTest {

    @Test fun `default values match spec`() {
        val c = Config()
        assertEquals(200, c.maxHttpRecords)
        assertEquals(20, c.maxWebSocketSessions)
        assertEquals(500, c.maxFramesPerSession)
        assertEquals(64 * 1024, c.maxBodyBytes)
        assertEquals(64 * 1024, c.maxFrameBytes)
        assertEquals(3_000L, c.autoScrollPauseAfterUserScrollMs)
    }

    @Test fun `custom values are stored`() {
        val c = Config(maxHttpRecords = 500, maxFramesPerSession = 1000)
        assertEquals(500, c.maxHttpRecords)
        assertEquals(1000, c.maxFramesPerSession)
    }
}
