package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.startup.analyzer.CriticalPath
import com.debugtools.startup.analyzer.StartupAnalyzer
import com.debugtools.startup.protocol.StartupSession

/** Scrollable panel: session list -> tap -> session detail (summary + Gantt/DAG toggle + issues). */
@SuppressLint("ViewConstructor")
class StartupRootView(
    context: Context,
    private val sessions: List<StartupSession>
) : ScrollView(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(12), p(12), p(12)) }
    private var showingDag = false
    private var picked: StartupSession? = null

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(StartupColors.BG)
        addView(content)
        showList()
    }

    private fun showList() {
        picked = null
        content.removeAllViews()
        content.addView(header("启动链路 · 最近 ${sessions.size} 次"))
        content.addView(SessionListView(context, sessions) { showDetail(it) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun showDetail(s: StartupSession) {
        picked = s
        content.removeAllViews()
        content.addView(backBtn())
        val crit = CriticalPath.of(s)
        val totalMs = (s.completedUptimeMs ?: s.steps.mapNotNull { it.endUptimeMs }.maxOrNull() ?: s.launchUptimeMs) - s.launchUptimeMs
        content.addView(header("总耗时 ${totalMs}ms · 关键路径: ${crit.joinToString("→")}"))
        content.addView(toggleBtn())
        if (showingDag) content.addView(DagView(context, s), lp())
        else content.addView(GanttView(context, s, crit.toSet()), lp())
        content.addView(header("⚠ 诊断"))
        val issues = StartupAnalyzer.analyze(s)
        if (issues.isEmpty()) content.addView(dim("无异常"))
        else issues.forEach { content.addView(dim("• [${it.type}] ${it.stepName ?: ""} ${it.detail}")) }
    }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) }

    private fun header(t: String) = TextView(context).apply {
        text = t; setTextColor(StartupColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }

    private fun dim(t: String) = TextView(context).apply {
        text = t; setTextColor(StartupColors.TEXT_DIM); textSize = 11f; setPadding(0, p(2), 0, p(2))
    }

    private fun toggleBtn() = TextView(context).apply {
        text = if (showingDag) "切到甘特图" else "切到依赖图"
        setTextColor(StartupColors.TEXT); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, p(8), 0, p(8))
        background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StartupColors.ACCENT) }
        setOnClickListener { showingDag = !showingDag; picked?.let { showDetail(it) } }
    }

    private fun backBtn() = TextView(context).apply {
        text = "← 返回列表"; setTextColor(StartupColors.ACCENT); textSize = 12f; setPadding(0, p(4), 0, p(8))
        setOnClickListener { showingDag = false; showList() }
    }

}
