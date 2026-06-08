package com.debugtools.core.window

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.WindowManager
import com.debugtools.core.module.DebugModule
import com.debugtools.core.window.view.FloatingRootView

internal class FloatingWindowManager(
    private val context: Context,
    private val modeManager: DisplayModeManager,
    private val briefOrientation: BriefOrientation
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FloatingRootView? = null

    private fun buildLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    )

    fun init(modules: List<DebugModule>) {
        if (!Settings.canDrawOverlays(context)) throw OverlayPermissionException()
        val params = buildLayoutParams()
        val view = FloatingRootView(context, modeManager, briefOrientation, windowManager, params)
        view.setModules(modules)
        windowManager.addView(view, params)
        rootView = view
    }

    fun destroy() {
        rootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        rootView = null
    }
}
