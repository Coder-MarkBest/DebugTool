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
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : FrameLayout(context) {

    private val expandedView = ExpandedView(context)
    private val minimizedView = MinimizedView(context, layoutParams) { syncWindowLayout() }
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

    /** Called by children when they mutate [layoutParams] (e.g. drag). */
    private fun syncWindowLayout() {
        // Only update if this root view is actually attached to the WindowManager
        if (isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(this, layoutParams)
            } catch (_: IllegalArgumentException) {
                // Window was removed between detection and update; safe to ignore
            }
        }
    }

}
