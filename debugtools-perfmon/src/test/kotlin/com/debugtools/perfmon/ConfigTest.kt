package com.debugtools.perfmon

import org.junit.Assert.*
import org.junit.Test

class ConfigTest {

    @Test fun `default values match spec`() {
        val c = Config()
        assertEquals(10, c.updateIntervalSec)
        assertEquals(30, c.windowMin)
        assertEquals(50, c.cpuOrangeThreshold)
        assertEquals(80, c.cpuRedThreshold)
        assertEquals(0, c.pssRedThresholdMb)
        assertEquals(10, c.topThreadCount)
    }

    @Test fun `derived window in seconds`() {
        val c = Config(windowMin = 30, updateIntervalSec = 10)
        assertEquals(1800, c.windowSec)
    }

    @Test fun `custom values are stored`() {
        val c = Config(updateIntervalSec = 20, windowMin = 60, topThreadCount = 20)
        assertEquals(20, c.updateIntervalSec)
        assertEquals(60, c.windowMin)
        assertEquals(20, c.topThreadCount)
    }
}
