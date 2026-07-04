package com.debugtools.core.window

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.debugtools.core.module.DebugModule
import com.debugtools.core.recording.DebugRecordingManager
import com.debugtools.core.window.view.FloatingRootView

internal class FloatingWindowManager(
    private val context: Context,
    private val modeManager: DisplayModeManager,
    private val briefOrientation: BriefOrientation,
    private val recordingManager: DebugRecordingManager
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = context.resources.displayMetrics
    private val expandedWidth = (dm.widthPixels * 0.42f).toInt()
    private val buttonSizePx = (56 * dm.density).toInt()
    private val briefStripPx = (44 * dm.density).toInt()
    private var rootView: FloatingRootView? = null

    // Shared mutable params — mutated on mode change, then updateViewLayout called
    private val layoutParams = WindowManager.LayoutParams(
        expandedWidth,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
    }

    fun init(modules: List<DebugModule>) {
        if (!Settings.canDrawOverlays(context)) throw OverlayPermissionException()
        val view = FloatingRootView(context, modeManager, briefOrientation, windowManager, layoutParams, recordingManager)
        view.setModules(modules)
        windowManager.addView(view, layoutParams)
        rootView = view

        modeManager.addListener { mode ->
            applyModeParams(mode)
            rootView?.let { windowManager.updateViewLayout(it, layoutParams) }
        }
    }

    fun destroy() {
        rootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
    }

    private fun applyModeParams(mode: DisplayMode) {
        when (mode) {
            DisplayMode.EXPANDED -> {
                layoutParams.width = expandedWidth
                layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
                layoutParams.gravity = Gravity.TOP or Gravity.END
                layoutParams.x = 0
                layoutParams.y = 0
            }
            DisplayMode.MINIMIZED -> {
                layoutParams.width = buttonSizePx.toInt()
                layoutParams.height = buttonSizePx.toInt()
                // Absolute positioning for drag; start at bottom-right corner
                layoutParams.gravity = Gravity.TOP or Gravity.START
                layoutParams.x = dm.widthPixels - buttonSizePx.toInt()
                layoutParams.y = (dm.heightPixels * 0.65f).toInt()
            }
            DisplayMode.BRIEF -> {
                if (briefOrientation == BriefOrientation.VERTICAL) {
                    layoutParams.width = briefStripPx.toInt()
                    layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
                    layoutParams.gravity = Gravity.TOP or Gravity.END
                } else {
                    layoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
                    layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                    layoutParams.gravity = Gravity.TOP or Gravity.START
                }
                layoutParams.x = 0
                layoutParams.y = 0
            }
        }
    }
}
