package com.debugtools.core.window.view

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.debugtools.core.module.DebugModule
import com.debugtools.core.recording.DebugRecordingManager
import com.debugtools.core.recording.HtmlRecordingReportWriter
import com.debugtools.core.window.BriefOrientation
import com.debugtools.core.window.DisplayMode
import com.debugtools.core.window.DisplayModeManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class FloatingRootView(
    context: Context,
    private val modeManager: DisplayModeManager,
    briefOrientation: BriefOrientation,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val recordingManager: DebugRecordingManager,
    private val onResizeExpandedBy: (Int) -> Unit = {}
) : FrameLayout(context) {

    private val expandedView = ExpandedView(context)
    private val minimizedView = MinimizedView(context, layoutParams) { syncWindowLayout() }
    private val briefView = BriefView(context, briefOrientation)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recordingBar = RecordingBarView(
        context = context,
        onStart = { startRecording() },
        onStop = { stopRecording() }
    )
    private var modules: List<DebugModule> = emptyList()
    private var lastSavedPath: String? = null
    private var resizeLastRawX: Float? = null

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
            DisplayMode.EXPANDED  -> addView(buildExpandedContainer(), fill)
            DisplayMode.MINIMIZED -> addView(minimizedView, fill)
            DisplayMode.BRIEF     -> { refreshBriefItems(); addView(briefView, fill) }
        }
    }

    private fun buildExpandedContainer(): View {
        val active = recordingManager.isActive()
        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(DebugToolsTheme.background)
        }
        shell.addView(buildResizeHandle(), LinearLayout.LayoutParams(
            DebugToolsTheme.dp(resources, 10),
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DebugToolsTheme.background)
        }
        detach(recordingBar)
        recordingBar.setLastSavedPath(lastSavedPath)
        recordingBar.render(recordingManager.state.value)
        container.addView(recordingBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val body = FrameLayout(context)
        detach(expandedView)
        body.addView(expandedView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        if (active) body.addView(buildInteractionBlocker(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        container.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        shell.addView(container, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        ))
        return shell
    }

    private fun buildResizeHandle(): View =
        View(context).apply {
            setBackgroundColor(DebugToolsTheme.divider)
            contentDescription = "拖拽调整面板宽度"
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        resizeLastRawX = event.rawX
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val last = resizeLastRawX ?: event.rawX
                        val delta = (last - event.rawX).toInt()
                        resizeLastRawX = event.rawX
                        onResizeExpandedBy(delta)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resizeLastRawX = null
                        true
                    }
                    else -> false
                }
            }
        }

    private fun buildInteractionBlocker(): View =
        TextView(context).apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            setTextColor(DebugToolsTheme.primaryText)
            textSize = 14f
            gravity = Gravity.CENTER
            text = "录制中，仅允许停止录制"
            isClickable = true
            isFocusable = true
        }

    private fun startRecording() {
        runCatching {
            val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "debugtools-recordings")
            recordingManager.start(modules, root)
            lastSavedPath = null
            applyMode(DisplayMode.EXPANDED)
        }.onFailure {
            Toast.makeText(context, it.message ?: "开始录制失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        recordingBar.setBusy(true)
        uiScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = recordingManager.stop()
                    val html = HtmlRecordingReportWriter().write(report, File(report.rootDir, "report.html"))
                    report.rootDir to html
                }
            }
            recordingBar.setBusy(false)
            result.fold(
                onSuccess = { (dir, html) ->
                    lastSavedPath = dir.absolutePath
                    Toast.makeText(context, "录制已保存: ${html.absolutePath}", Toast.LENGTH_LONG).show()
                },
                onFailure = {
                    Toast.makeText(context, it.message ?: "停止录制失败", Toast.LENGTH_SHORT).show()
                }
            )
            applyMode(DisplayMode.EXPANDED)
        }
    }

    private fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiScope.cancel()
    }

}
