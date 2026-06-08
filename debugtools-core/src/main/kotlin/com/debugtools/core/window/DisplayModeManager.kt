package com.debugtools.core.window

import java.util.concurrent.CopyOnWriteArrayList

internal class DisplayModeManager {
    var currentMode: DisplayMode = DisplayMode.EXPANDED
        private set

    private val listeners = CopyOnWriteArrayList<(DisplayMode) -> Unit>()

    fun setMode(mode: DisplayMode) {
        if (mode == currentMode) return
        currentMode = mode
        listeners.forEach { it(mode) }
    }

    fun addListener(listener: (DisplayMode) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (DisplayMode) -> Unit) {
        listeners.remove(listener)
    }
}
