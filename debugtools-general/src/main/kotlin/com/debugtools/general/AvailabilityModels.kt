package com.debugtools.general

fun interface AvailabilityItemSource {
    fun getAvailabilityItems(): List<AvailabilityItem>
}

enum class AvailabilityStatus(val severity: Int) {
    AVAILABLE(0),
    UNKNOWN(1),
    DEGRADED(2),
    UNAVAILABLE(3)
}

data class AvailabilityItem(
    val id: String,
    val title: String,
    val status: AvailabilityStatus = AvailabilityStatus.UNKNOWN,
    val message: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis()
)
