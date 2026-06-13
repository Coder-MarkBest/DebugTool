package com.debugtools.perfmon.data

/**
 * Thread-safe ring buffer keyed by wall-clock timestamp.
 * Capacity = windowSec / intervalSec + 1.
 * When full, [add] evicts the oldest entry.
 *
 * [snapshot] returns an independent immutable list so callers can iterate without
 * worrying about concurrent mutation.
 */
class TimeSeries<T>(private val windowSec: Int, private val intervalSec: Int) {
    private val capacity = windowSec / intervalSec + 1
    private val buffer = ArrayDeque<TimedValue<T>>(capacity)

    @Synchronized
    fun add(timestamp: Long, value: T) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(TimedValue(timestamp, value))
    }

    @Synchronized
    fun snapshot(): List<TimedValue<T>> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}

data class TimedValue<T>(val timestamp: Long, val value: T)
