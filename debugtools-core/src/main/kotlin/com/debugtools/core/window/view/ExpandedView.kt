package com.debugtools.core.window.view

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.DebugModule
import com.debugtools.core.overview.OverviewAggregator

internal class ExpandedView(context: Context) : LinearLayout(context) {
    private val tabRailWidthPx = DebugToolsTheme.dp(resources, DebugToolsTheme.tabRailWidthDp)
    private val tabBar = TabBarView(context)
    private val contentFrame = FrameLayout(context)
    private val overviewView = OverviewView(context)
    private var modules: List<DebugModule> = emptyList()
    private var contentViews: List<View> = emptyList()

    /** Invoked when the user taps the minimize button. */
    var onMinimizeClick: (() -> Unit)? = null
    /** Invoked when the user taps the brief-mode button. */
    var onBriefClick: (() -> Unit)? = null
    /** Invoked when the user horizontally drags the tab rail. */
    var onResizeDrag: ((Int) -> Unit)? = null
    var onResizeStart: (() -> Unit)? = null
    var onResizeEnd: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(DebugToolsTheme.background)

        val rail = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(DebugToolsTheme.panel)
        }
        tabBar.onTabSelected = { showContent(it) }
        tabBar.onResizeStart = { onResizeStart?.invoke() }
        tabBar.onResizeDrag = { onResizeDrag?.invoke(it) }
        tabBar.onResizeEnd = { onResizeEnd?.invoke() }
        rail.addView(
            tabBar,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val controls = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(DebugToolsTheme.panel)
        }
        controls.addView(buildControlButton("▭", "切到最小化模式") { onMinimizeClick?.invoke() })
        controls.addView(buildControlButton("≡", "切到简要信息模式") { onBriefClick?.invoke() })
        rail.addView(
            controls,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        contentFrame.setBackgroundColor(DebugToolsTheme.background)
        addView(rail, LayoutParams(tabRailWidthPx, LayoutParams.MATCH_PARENT))
        addView(contentFrame, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    private fun buildControlButton(label: String, contentDesc: String,
                                    onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(DebugToolsTheme.primaryText)
            gravity = Gravity.CENTER
            contentDescription = contentDesc
            setBackgroundColor(DebugToolsTheme.panel)
            val px48 = DebugToolsTheme.dp(resources, 48)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, px48)
            setOnClickListener { onClick() }
        }

    fun setModules(modules: List<DebugModule>) {
        this.modules = modules
        contentViews = modules.map { it.createContentView(context) }
        tabBar.setTabTitles(listOf("总览") + modules.map { it.tabTitle })
        showContent(0)
    }

    private fun showContent(index: Int) {
        contentFrame.removeAllViews()
        if (index == 0) {
            overviewView.update(OverviewAggregator.collect(modules)) { moduleId ->
                openModuleTab(moduleId)
            }
            contentFrame.addView(overviewView)
            return
        }
        contentViews.getOrNull(index - 1)?.let { contentFrame.addView(it) }
    }

    private fun openModuleTab(moduleId: String) {
        val moduleIndex = modules.indexOfFirst { it.moduleId == moduleId }
        if (moduleIndex < 0) return
        tabBar.selectTab(moduleIndex + 1)
    }

    internal fun tabRailWidthPxForTest(): Int = tabRailWidthPx

    internal fun dispatchTabRailResizeDragForTest(deltaPx: Int) {
        tabBar.dispatchResizeDragForTest(deltaPx)
    }

    internal fun dispatchTabRailResizeStartForTest() {
        tabBar.dispatchResizeStartForTest()
    }

    internal fun dispatchTabRailResizeEndForTest() {
        tabBar.dispatchResizeEndForTest()
    }

    internal fun tabTitleForTest(index: Int): String? =
        tabBar.tabTitleForTest(index)

    internal fun selectedTabIndexForTest(): Int =
        tabBar.selectedIndexForTest()

    internal fun clickOverviewRowForTest(moduleId: String) {
        overviewView.performRowClickForTest(moduleId)
    }
}
