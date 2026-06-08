package com.debugtools.core.window.view

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.debugtools.core.module.DebugModule

internal class ExpandedView(context: Context) : LinearLayout(context) {
    private val tabBar = TabBarView(context)
    private val contentFrame = FrameLayout(context)
    private var contentViews: List<View> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(android.graphics.Color.parseColor("#CC1A1A2E"))
        tabBar.onTabSelected = { showContent(it) }
        addView(tabBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
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
