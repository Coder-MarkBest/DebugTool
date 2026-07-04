package com.debugtools.core.overview

import com.debugtools.core.module.DebugModule

object OverviewAggregator {
    fun collect(modules: List<DebugModule>): List<OverviewItem> =
        modules
            .flatMap { (it as? OverviewProvider)?.getOverviewItems().orEmpty() }
            .sortedWith(compareBy<OverviewItem> { rank(it.status) }.thenBy { it.title })

    private fun rank(status: OverviewStatus): Int = when (status) {
        OverviewStatus.ERROR -> 0
        OverviewStatus.WARNING -> 1
        OverviewStatus.RECORDING -> 2
        OverviewStatus.UNKNOWN -> 3
        OverviewStatus.OK -> 4
    }
}
