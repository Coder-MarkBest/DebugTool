package com.debugtools.timeline

import com.debugtools.core.ipc.model.DebugEvent
import java.util.ArrayDeque

class EventRepository(private val maxSize: Int = 500) {

    private val deque = ArrayDeque<DebugEvent>()

    @Synchronized fun add(event: DebugEvent) {
        if (deque.size >= maxSize) deque.pollFirst()
        deque.addLast(event)
    }

    @Synchronized fun snapshot(): List<DebugEvent> = deque.toList()

    @Synchronized fun clear() = deque.clear()
}
