package com.debugtools.audiomon.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEventTest {

    @Test
    fun `toJson carries stream type label and detail`() {
        val e = AnomalyEvent(StreamId.B, 3010L, AnomalyType.CLIPPING, "peak 0.99")
        val j = e.toJson()
        assertEquals("B", j.getString("stream"))
        assertEquals(3010L, j.getLong("timeMs"))
        assertEquals("CLIPPING", j.getString("type"))
        assertEquals("削波", j.getString("typeLabel"))
        assertEquals("peak 0.99", j.getString("detail"))
    }

    @Test
    fun `every anomaly type has a non-empty hint`() {
        assertTrue(AnomalyType.entries.all { it.hint.isNotBlank() && it.label.isNotBlank() })
    }
}
