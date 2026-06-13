package com.debugtools.perfmon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimedValue
import com.debugtools.perfmon.view.widget.LineChartView
import com.debugtools.perfmon.view.widget.ThreadBarView
import com.debugtools.perfmon.view.widget.ThreadStateView

@SuppressLint("ViewConstructor")
class ProcessDetailView(context: Context, private val config: Config) : ScrollView(context) {

    private val cpuChart = LineChartView(context)
    private val memChart = LineChartView(context)
    private val threadBar = ThreadBarView(context)
    private val threadState = ThreadStateView(context)
    private val title: TextView
    private val pssText: TextView
    private val placeholder: TextView

    init {
        setBackgroundColor(Color.parseColor("#1A1A2E"))
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        title = TextView(context).apply {
            text = "请在左侧选择一个进程"
            setTextColor(Color.WHITE); textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        pssText = TextView(context).apply {
            setTextColor(Color.parseColor("#E2E8F0")); textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        placeholder = TextView(context).apply {
            text = "（等待数据）"
            setTextColor(Color.parseColor("#A0AEC0")); textSize = 14f
        }
        container.addView(title)
        container.addView(label("CPU% 趋势"))
        container.addView(cpuChart)
        container.addView(label("内存 PSS（KB）"))
        container.addView(memChart)
        container.addView(pssText)
        container.addView(label("Top ${config.topThreadCount} 线程 CPU%"))
        container.addView(threadBar)
        container.addView(label("线程状态分布"))
        container.addView(threadState)
        container.addView(placeholder)
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#63B3ED"))
        textSize = 12f
        setPadding(0, 16, 0, 8)
    }

    fun update(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>) {
        if (cpuSeries.isEmpty()) {
            title.text = "请在左侧选择一个进程"
            placeholder.visibility = android.view.View.VISIBLE
            return
        }
        placeholder.visibility = android.view.View.GONE
        val last = cpuSeries.last().value
        val displayName = (last.target as? com.debugtools.perfmon.data.ProcessTarget.ByName)?.processName
            ?: "pid ${last.pid}"
        title.text = "$displayName (${last.pid ?: "-"})"

        cpuChart.setSeries(listOf(
            LineChartView.Series("CPU%", Color.parseColor("#63B3ED"),
                cpuSeries.map { it.value.cpuPercent })
        ), yAxisMax = null)

        if (detail != null) {
            pssText.text = "PSS 总: ${detail.totalPssKb} KB  " +
                "Java: ${detail.dalvikPssKb} KB  " +
                "Native: ${detail.nativePssKb} KB  " +
                "Other: ${detail.otherPssKb} KB"
            memChart.setSeries(listOf(
                LineChartView.Series("PSS", Color.parseColor("#68D391"),
                    cpuSeries.map { it.value.rssBytes / 1024f })
            ), yAxisMax = null)
            threadBar.setThreads(detail.threads, maxPercent = (detail.threads.maxOfOrNull { it.cpuPercent } ?: 100f))
            threadState.setDistribution(detail.threadStateDistribution)
        } else {
            pssText.text = "PSS 数据未就绪（Tier 2 采样中…）"
        }
    }
}
