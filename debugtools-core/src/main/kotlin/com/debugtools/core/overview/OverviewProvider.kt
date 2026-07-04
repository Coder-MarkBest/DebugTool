package com.debugtools.core.overview

interface OverviewProvider {
    fun getOverviewItems(): List<OverviewItem>
}

data class OverviewItem(
    val moduleId: String,
    val title: String,
    val status: OverviewStatus,
    val primaryText: String,
    val secondaryText: String? = null,
    val metrics: List<OverviewMetric> = emptyList()
)

enum class OverviewStatus {
    OK,
    WARNING,
    ERROR,
    RECORDING,
    UNKNOWN
}

data class OverviewMetric(
    val label: String,
    val value: String,
    val status: OverviewStatus = OverviewStatus.UNKNOWN
)
