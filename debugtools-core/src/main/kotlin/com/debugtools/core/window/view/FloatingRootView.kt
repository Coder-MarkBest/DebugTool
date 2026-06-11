package com.debugtools.core.window.view

import android.content.Context
import android.view.WindowManager
import android.widget.FrameLayout
import com.debugtools.core.module.DebugModule
import com.debugtools.core.window.BriefOrientation
import com.debugtools.core.window.DisplayMode
import com.debugtools.core.window.DisplayModeManager

internal class FloatingRootView(
    context: Context,
    private val modeManager: DisplayModeManager,
    briefOrientation: BriefOrientation,
    windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : FrameLayout(context) {

    private val expandedView = ExpandedView(context)
    private val minimizedView = MinimizedView(context, windowManager, layoutParams)
    private val briefView = BriefView(context, briefOrientation)
    private var modules: List<DebugModule> = emptyList()

    init {
        expandedView.onMinimizeClick = { modeManager.setMode(DisplayMode.MINIMIZED) }
        expandedView.onBriefClick = { modeManager.setMode(DisplayMode.BRIEF) }
        minimizedView.onClick = { modeManager.setMode(DisplayMode.EXPANDED) }
        minimizedView.onLongClick = { modeManager.setMode(DisplayMode.BRIEF) }
        briefView.onClick = { modeManager.setMode(DisplayMode.EXPANDED) }
        briefView.onLongClick = { modeManager.setMode(DisplayMode.MINIMIZED) }
        modeManager.addListener { applyMode(it) }
        applyMode(modeManager.currentMode)
    }

    fun setModules(modules: List<DebugModule>) {
        this.modules = modules
        expandedView.setModules(modules)
        refreshBriefItems()
    }

    private fun applyMode(mode: DisplayMode) {
        removeAllViews()
        val fill = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        when (mode) {
            DisplayMode.EXPANDED  -> addView(expandedView, fill)
            DisplayMode.MINIMIZED -> addView(minimizedView, fill)
            DisplayMode.BRIEF     -> { refreshBriefItems(); addView(briefView, fill) }
        }
    }

    private fun refreshBriefItems() {
        briefView.update(modules.flatMap { it.getBriefItems() })
    }
}
