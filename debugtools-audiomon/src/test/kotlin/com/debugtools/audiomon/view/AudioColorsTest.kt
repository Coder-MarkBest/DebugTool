package com.debugtools.audiomon.view

import com.debugtools.audiomon.anomaly.AnomalyType
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioColorsTest {

    @Test
    fun `every anomaly type has a distinct opaque color`() {
        val colors = AnomalyType.entries.map { AudioColors.anomalyTypeColor(it) }
        assertTrue(colors.all { (it ushr 24) and 0xFF == 0xFF }) // all opaque
        assertTrue(colors.toSet().size == AnomalyType.entries.size) // all distinct
    }
}
