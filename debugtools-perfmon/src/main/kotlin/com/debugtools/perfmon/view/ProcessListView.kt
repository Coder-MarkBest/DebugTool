package com.debugtools.perfmon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.presenter.ProcessRow

@SuppressLint("ViewConstructor")
class ProcessListView(context: Context, private val config: Config) : ScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#1A202C"))
    }
    var onSelect: ((String) -> Unit)? = null

    init {
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun submit(rows: List<ProcessRow>) {
        container.removeAllViews()
        for (row in rows) {
            container.addView(buildRow(row))
        }
    }

    private fun buildRow(row: ProcessRow): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = (96 * resources.displayMetrics.density).toInt()
        setPadding(24, 16, 24, 16)
        setBackgroundColor(if (row.selected) Color.parseColor("#2D3748") else Color.TRANSPARENT)
        setOnClickListener { onSelect?.invoke(row.targetKey) }

        addView(TextView(context).apply {
            text = (if (row.selected) "▶ " else "") +
                (if (!row.alive) "✗ " else "● ") +
                row.displayName +
                (row.pid?.let { " ($it)" } ?: "")
            setTextColor(if (row.alive) Color.WHITE else Color.parseColor("#A0AEC0"))
            textSize = 15f
        })
        addView(TextView(context).apply {
            text = "CPU %.0f%%   RSS %s   线程 %d".format(
                row.cpuPercent, formatBytes(row.rssBytes), row.threadCount
            )
            setTextColor(cpuColor(row.cpuPercent))
            textSize = 13f
            setPadding(0, 8, 0, 0)
        })
    }

    private fun cpuColor(percent: Float): Int = when {
        percent < config.cpuOrangeThreshold -> Color.parseColor("#68D391")
        percent < config.cpuRedThreshold -> Color.parseColor("#FBD38D")
        else -> Color.parseColor("#FC8181")
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        b < 1024 * 1024 * 1024 -> "${b / (1024 * 1024)}MB"
        else -> "${b / (1024 * 1024 * 1024)}GB"
    }
}
