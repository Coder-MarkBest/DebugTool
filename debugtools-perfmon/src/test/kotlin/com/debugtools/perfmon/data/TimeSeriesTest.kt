package com.debugtools.perfmon.data

import org.junit.Assert.*
import org.junit.Test

class TimeSeriesTest {

    @Test fun `add stores values in insertion order`() {
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        s.add(timestamp = 1L, value = 10)
        s.add(timestamp = 2L, value = 20)
        val snap = s.snapshot()
        assertEquals(2, snap.size)
        assertEquals(10, snap[0].value)
        assertEquals(20, snap[1].value)
    }

    @Test fun `evicts oldest when window is full`() {
        // windowSec=30, intervalSec=10 → capacity = 30/10 + 1 = 4
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        listOf(1, 2, 3, 4, 5).forEach { s.add(it.toLong(), it) }
        val snap = s.snapshot()
        assertEquals(4, snap.size)
        assertEquals(listOf(2, 3, 4, 5), snap.map { it.value })
    }

    @Test fun `snapshot returns immutable copy`() {
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        s.add(1L, 100)
        val snap = s.snapshot()
        s.add(2L, 200)
        assertEquals(1, snap.size)  // earlier snapshot unaffected
    }

    @Test fun `empty snapshot when no data`() {
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        assertTrue(s.snapshot().isEmpty())
    }
}
