package com.debugtools.core.window.view

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.DebugModule

internal class ExpandedView(context: Context) : LinearLayout(context) {
    private val tabBar = TabBarView(context)
    private val contentFrame = FrameLayout(context)
    private var contentViews: List<View> = emptyList()

    /** Invoked when the user taps the minimize button. */
    var onMinimizeClick: (() -> Unit)? = null
    /** Invoked when the user taps the brief-mode button. */
    var onBriefClick: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#CC1A1A2E"))

        // Header row: TabBar (flex) + two mode-switch buttons on the right
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.parseColor("#2D3748"))
        }
        tabBar.onTabSelected = { showContent(it) }
        header.addView(
            tabBar,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(buildControlButton("▭", "切到最小化模式") { onMinimizeClick?.invoke() })
        header.addView(buildControlButton("≡", "切到简要信息模式") { onBriefClick?.invoke() })

        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildControlButton(label: String, contentDesc: String,
                                    onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = contentDesc
            val px48 = (48 * resources.displayMetrics.density).toInt()
            layoutParams = LayoutParams(px48, LayoutParams.MATCH_PARENT)
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
}
