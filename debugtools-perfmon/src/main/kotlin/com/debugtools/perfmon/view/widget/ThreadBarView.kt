package com.debugtools.perfmon.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.perfmon.data.ThreadInfo

/**
 * Renders a Top-N thread list as labeled horizontal bars. Each row shows the thread
 * name on the left, the CPU% number, then a bar whose width reflects percent.
 */
@SuppressLint("ViewConstructor")
class ThreadBarView(context: Context) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setPadding(16, 16, 16, 16)
    }

    fun setThreads(threads: List<ThreadInfo>, maxPercent: Float) {
        removeAllViews()
        for (t in threads) {
            addView(buildRow(t, maxPercent))
        }
    }

    private fun buildRow(t: ThreadInfo, maxPercent: Float): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        row.addView(TextView(context).apply {
            text = t.name
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f)
        })
        row.addView(TextView(context).apply {
            text = "%.1f%%".format(t.cpuPercent)
            setTextColor(Color.parseColor("#CBD5E0"))
            textSize = 14f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        // Visual bar
        val bar = View(context).apply {
            val ratio = if (maxPercent > 0) (t.cpuPercent / maxPercent).coerceIn(0f, 1f) else 0f
            setBackgroundColor(barColor(t.cpuPercent))
            layoutParams = LayoutParams(0, (16 * resources.displayMetrics.density).toInt(), ratio * 4f)
        }
        row.addView(bar)
        // Spacer to fill remaining flex
        row.addView(View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, (1f - ((t.cpuPercent / maxPercent).coerceIn(0f, 1f))) * 4f)
        })
        return row
    }

    private fun barColor(percent: Float): Int = when {
        percent < 30f -> Color.parseColor("#63B3ED")
        percent < 60f -> Color.parseColor("#FBD38D")
        else -> Color.parseColor("#FC8181")
    }
}
