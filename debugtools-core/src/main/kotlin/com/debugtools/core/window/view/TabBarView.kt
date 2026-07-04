package com.debugtools.core.window.view

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.core.module.DebugModule

internal class TabBarView(context: Context) : ScrollView(context) {
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private var selectedIndex = 0
    var onTabSelected: ((Int) -> Unit)? = null

    init {
        isFillViewport = false
        setBackgroundColor(DebugToolsTheme.panel)
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTabs(modules: List<DebugModule>) {
        container.removeAllViews()
        modules.forEachIndexed { index, module ->
            container.addView(buildTab(context, module.tabTitle, index == selectedIndex) {
                selectTab(index)
            })
        }
    }

    private fun buildTab(context: Context, title: String, selected: Boolean,
                         onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = title
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(6), dp(12), dp(6), dp(12))
            setTextColor(if (selected) DebugToolsTheme.primaryText else DebugToolsTheme.secondaryText)
            setBackgroundColor(if (selected) DebugToolsTheme.secondaryPanel else DebugToolsTheme.panel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun selectTab(index: Int) {
        selectedIndex = index
        onTabSelected?.invoke(index)
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? TextView)?.apply {
                setTextColor(if (i == index) DebugToolsTheme.primaryText else DebugToolsTheme.secondaryText)
                setBackgroundColor(if (i == index) DebugToolsTheme.secondaryPanel else DebugToolsTheme.panel)
            }
        }
    }

    internal fun firstTabForTest(): TextView? =
        container.getChildAt(0) as? TextView

    private fun dp(value: Int): Int = DebugToolsTheme.dp(resources, value)
}
