package com.debugtools.timeline

import com.debugtools.core.ipc.model.DebugEvent

interface TimelineView {
    fun showEvents(events: List<DebugEvent>)
}
