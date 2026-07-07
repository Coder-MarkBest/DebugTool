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
import com.debugtools.stability.protocol.CrashEntry

/** Scrollable panel: process status bar → search button → crash list. */
@SuppressLint("ViewConstructor")
class StabilityRootView(
    context: Context,
    private val onSearchClick: () -> Unit
) : ScrollView(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(12), p(12), p(12)) }

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(StabilityColors.BG)
        addView(content)
        renderLoading()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    fun renderLoading() {
        content.removeAllViews()
        content.addView(header("进程状态"))
        content.addView(message("扫描中..."), lp())
    }

    fun renderError(message: String) {
        content.removeAllViews()
        content.addView(header("稳定性"))
        content.addView(searchButton(), lp())
        content.addView(message("扫描失败: $message"), lp())
    }

    fun renderData(status: Map<String, Boolean>, entries: List<CrashEntry>) {
        content.removeAllViews()

        content.addView(header("进程状态"))
        content.addView(ProcessStatusBar(context, status), lp())

        content.addView(searchButton(), lp())

        content.addView(header("崩溃记录"))
        if (status.isNotEmpty() && entries.isEmpty()) {
            content.addView(message("未发现目标进程崩溃记录；非系统应用或权限不足时系统源可能不可读。"), lp())
        }
        content.addView(CrashListView(context, entries), lp())
    }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) }

    private fun searchButton() = Button(context).apply {
        text = "立即搜索"
        setTextColor(StabilityColors.TEXT); textSize = 12f
        background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StabilityColors.ACCENT) }
        setOnClickListener { onSearchClick() }
    }

    private fun header(t: String) = TextView(context).apply {
        text = t; setTextColor(StabilityColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }

    private fun message(t: String) = TextView(context).apply {
        text = t; setTextColor(StabilityColors.TEXT_DIM); textSize = 12f
        setPadding(p(8), p(8), p(8), p(8))
    }
}
