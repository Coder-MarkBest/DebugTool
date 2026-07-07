package com.debugtools.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityModelsTest {
    @Test
    fun severityOrdersUnavailableBeforeDegradedBeforeUnknownBeforeAvailable() {
        val statuses = listOf(
            AvailabilityStatus.AVAILABLE,
            AvailabilityStatus.UNKNOWN,
            AvailabilityStatus.DEGRADED,
            AvailabilityStatus.UNAVAILABLE
        )

        assertEquals(
            listOf(
                AvailabilityStatus.UNAVAILABLE,
                AvailabilityStatus.DEGRADED,
                AvailabilityStatus.UNKNOWN,
                AvailabilityStatus.AVAILABLE
            ),
            statuses.sortedByDescending { it.severity }
        )
    }

    @Test
    fun itemDefaultsToUnknownWhenStatusIsNotProvided() {
        val item = AvailabilityItem(id = "privacy", title = "隐私协议")

        assertEquals(AvailabilityStatus.UNKNOWN, item.status)
        assertEquals("", item.message)
        assertTrue(item.updatedAtMillis > 0L)
    }
}
