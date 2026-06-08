package com.debugtools.core.window

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DisplayModeManagerTest {
    private lateinit var manager: DisplayModeManager

    @Before fun setUp() {
        manager = DisplayModeManager()
    }

    @Test fun `initial mode is EXPANDED`() {
        assertEquals(DisplayMode.EXPANDED, manager.currentMode)
    }

    @Test fun `setMode updates currentMode`() {
        manager.setMode(DisplayMode.MINIMIZED)
        assertEquals(DisplayMode.MINIMIZED, manager.currentMode)
    }

    @Test fun `listener notified on mode change`() {
        var notified: DisplayMode? = null
        manager.addListener { notified = it }
        manager.setMode(DisplayMode.BRIEF)
        assertEquals(DisplayMode.BRIEF, notified)
    }

    @Test fun `listener not notified when mode unchanged`() {
        var count = 0
        manager.addListener { count++ }
        manager.setMode(DisplayMode.EXPANDED)  // same as initial
        assertEquals(0, count)
    }

    @Test fun `removeListener stops notifications`() {
        var count = 0
        val listener: (DisplayMode) -> Unit = { count++ }
        manager.addListener(listener)
        manager.removeListener(listener)
        manager.setMode(DisplayMode.MINIMIZED)
        assertEquals(0, count)
    }

    @Test fun `multiple listeners all notified`() {
        val results = mutableListOf<DisplayMode>()
        manager.addListener { results.add(it) }
        manager.addListener { results.add(it) }
        manager.setMode(DisplayMode.BRIEF)
        assertEquals(2, results.size)
    }
}
