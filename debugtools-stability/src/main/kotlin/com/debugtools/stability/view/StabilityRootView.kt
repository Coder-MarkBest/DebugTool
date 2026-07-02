package com.debugtools.stability.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.stability.StabilityMonitor
import com.debugtools.stability.protocol.CrashEntry

/** Scrollable panel: process status bar → search button → crash list. */
@SuppressLint("ViewConstructor")
class StabilityRootView(context: Context) : ScrollView(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(12), p(12), p(12)) }
    private var entries: List<CrashEntry> = emptyList()

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(StabilityColors.BG)
        addView(content)
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    fun refresh() {
        val status = StabilityMonitor.processAliveStatus()
        entries = StabilityMonitor.searchNow()
        content.removeAllViews()

        content.addView(header("进程状态"))
        content.addView(ProcessStatusBar(context, status), lp())

        content.addView(Button(context).apply {
            text = "🔍 立即搜索"
            setTextColor(StabilityColors.TEXT); textSize = 12f
            background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StabilityColors.ACCENT) }
            setOnClickListener { refresh() }
        }, lp())

        content.addView(header("崩溃记录"))
        content.addView(CrashListView(context, entries), lp())
    }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) }

    private fun header(t: String) = TextView(context).apply {
        text = t; setTextColor(StabilityColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }
}
