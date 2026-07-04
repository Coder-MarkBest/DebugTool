package com.debugtools.core.window.view

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.DebugModule

internal class ExpandedView(context: Context) : LinearLayout(context) {
    private val tabRailWidthPx = DebugToolsTheme.dp(resources, 72)
    private val tabBar = TabBarView(context)
    private val contentFrame = FrameLayout(context)
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
        contentViews = modules.map { it.createContentView(context) }
        tabBar.setTabs(modules)
        if (modules.isNotEmpty()) showContent(0)
    }

    private fun showContent(index: Int) {
        contentFrame.removeAllViews()
        contentViews.getOrNull(index)?.let { contentFrame.addView(it) }
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
}
