package com.debugtools.core.window.view

import android.content.res.Resources
import android.graphics.Color
import com.debugtools.core.overview.OverviewStatus

internal object DebugToolsTheme {
    val background: Int = Color.parseColor("#0B1117")
    val panel: Int = Color.parseColor("#111A22")
    val secondaryPanel: Int = Color.parseColor("#16212B")
    val divider: Int = Color.parseColor("#25313D")
    val primaryText: Int = Color.parseColor("#F2F5F8")
    val secondaryText: Int = Color.parseColor("#9AA8B5")
    val success: Int = Color.parseColor("#35C46A")
    val warning: Int = Color.parseColor("#F6B33B")
    val danger: Int = Color.parseColor("#EF4E4E")

    const val tabRailWidthDp = 72
    const val rowMinHeightDp = 56
    const val rowPaddingHorizontalDp = 14
    const val rowPaddingVerticalDp = 10
    const val recordingBarHeightDp = 48

    fun colorFor(status: OverviewStatus): Int = when (status) {
        OverviewStatus.OK -> success
        OverviewStatus.WARNING -> warning
        OverviewStatus.ERROR -> danger
        OverviewStatus.RECORDING -> success
        OverviewStatus.UNKNOWN -> secondaryText
    }

    fun dp(resources: Resources, value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
