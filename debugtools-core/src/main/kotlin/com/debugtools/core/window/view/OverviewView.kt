package com.debugtools.core.window.view

import android.content.Context
import android.view.Gravity
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
            container.addView(buildRow(item, onModuleClick), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(1)
            })
        }
    }

    private fun buildRow(item: OverviewItem, onModuleClick: (String) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = rowTag(item.moduleId)
            isClickable = true
            setBackgroundColor(DebugToolsTheme.panel)
            minimumHeight = dp(DebugToolsTheme.rowMinHeightDp)
            setOnClickListener { onModuleClick(item.moduleId) }
            addView(View(context).apply {
                setBackgroundColor(DebugToolsTheme.colorFor(item.status))
            }, LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(DebugToolsTheme.rowPaddingHorizontalDp),
                    dp(DebugToolsTheme.rowPaddingVerticalDp),
                    dp(DebugToolsTheme.rowPaddingHorizontalDp),
                    dp(DebugToolsTheme.rowPaddingVerticalDp)
                )
                addView(TextView(context).apply {
                    text = item.title
                    textSize = 14f
                    setTextColor(DebugToolsTheme.primaryText)
                    maxLines = 1
                })
                addView(TextView(context).apply {
                    text = item.primaryText
                    textSize = 13f
                    setTextColor(DebugToolsTheme.colorFor(item.status))
                    maxLines = 1
                })
                item.secondaryText?.let { secondary ->
                    addView(TextView(context).apply {
                        text = secondary
                        textSize = 12f
                        setTextColor(DebugToolsTheme.secondaryText)
                        maxLines = 1
                    })
                }
                if (item.metrics.isNotEmpty()) {
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, 0)
                        item.metrics.take(4).forEach { metric ->
                            addView(TextView(context).apply {
                                text = "${metric.label} ${metric.value}"
                                textSize = 11f
                                setTextColor(DebugToolsTheme.colorFor(metric.status))
                                setPadding(0, 0, dp(12), 0)
                                maxLines = 1
                            })
                        }
                    })
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun emptyState(): View =
        TextView(context).apply {
            text = "暂无总览数据"
            textSize = 13f
            setTextColor(DebugToolsTheme.secondaryText)
            setPadding(
                dp(DebugToolsTheme.rowPaddingHorizontalDp),
                dp(DebugToolsTheme.rowPaddingVerticalDp),
                dp(DebugToolsTheme.rowPaddingHorizontalDp),
                dp(DebugToolsTheme.rowPaddingVerticalDp)
            )
        }

    internal fun performRowClickForTest(moduleId: String) {
        container.findViewWithTag<View>(rowTag(moduleId))?.performClick()
    }

    internal fun rowMinHeightForTest(moduleId: String): Int? =
        container.findViewWithTag<View>(rowTag(moduleId))?.minimumHeight

    private fun rowTag(moduleId: String): String = "overview-row:$moduleId"

    private fun dp(value: Int): Int = DebugToolsTheme.dp(resources, value)
}
