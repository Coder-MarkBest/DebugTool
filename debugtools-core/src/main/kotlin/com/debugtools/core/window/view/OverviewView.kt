package com.debugtools.core.window.view

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.core.overview.OverviewItem

internal class OverviewView(context: Context) : ScrollView(context) {
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    init {
        setBackgroundColor(DebugToolsTheme.background)
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun update(items: List<OverviewItem>, onModuleClick: (String) -> Unit) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(emptyState())
            return
        }
        items.forEach { item ->
            container.addView(buildRow(item, onModuleClick))
        }
    }

    private fun buildRow(item: OverviewItem, onModuleClick: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = rowTag(item.moduleId)
            isClickable = true
            setBackgroundColor(DebugToolsTheme.panel)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onModuleClick(item.moduleId) }
            addView(TextView(context).apply {
                text = item.title
                textSize = 14f
                setTextColor(DebugToolsTheme.primaryText)
            })
            addView(TextView(context).apply {
                text = item.primaryText
                textSize = 13f
                setTextColor(DebugToolsTheme.secondaryText)
            })
            item.secondaryText?.let { secondary ->
                addView(TextView(context).apply {
                    text = secondary
                    textSize = 12f
                    setTextColor(DebugToolsTheme.secondaryText)
                })
            }
        }

    private fun emptyState(): View =
        TextView(context).apply {
            text = "暂无总览数据"
            textSize = 13f
            setTextColor(DebugToolsTheme.secondaryText)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

    internal fun performRowClickForTest(moduleId: String) {
        container.findViewWithTag<View>(rowTag(moduleId))?.performClick()
    }

    private fun rowTag(moduleId: String): String = "overview-row:$moduleId"

    private fun dp(value: Int): Int = DebugToolsTheme.dp(resources, value)
}
