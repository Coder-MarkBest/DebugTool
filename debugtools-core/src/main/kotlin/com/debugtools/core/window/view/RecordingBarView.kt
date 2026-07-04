package com.debugtools.core.window.view

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.recording.RecordingState

internal class RecordingBarView(
    context: Context,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit
) : LinearLayout(context) {

    private val title = TextView(context)
    private val action = TextView(context)
    private var lastSavedPath: String? = null
    private var busy = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(8), dp(8), dp(8))
        setBackgroundColor(Color.parseColor("#18212B"))

        title.setTextColor(Color.WHITE)
        title.textSize = 13f
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        action.gravity = Gravity.CENTER
        action.textSize = 13f
        action.setTextColor(Color.WHITE)
        action.setPadding(dp(12), dp(7), dp(12), dp(7))
        action.setOnClickListener {
            if (busy) return@setOnClickListener
            val active = tag as? Boolean ?: false
            if (active) onStop() else onStart()
        }
        addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        render(RecordingState.Idle)
    }

    fun setBusy(value: Boolean) {
        busy = value
        action.alpha = if (value) 0.55f else 1f
        action.isEnabled = !value
    }

    fun setLastSavedPath(path: String?) {
        lastSavedPath = path
    }

    fun render(state: RecordingState) {
        val active = state is RecordingState.Active
        tag = active
        if (active) {
            val id = (state as RecordingState.Active).context.recordingId
            title.text = "全局录制中 · $id"
            action.text = "停止录制"
            action.setBackgroundColor(Color.parseColor("#B83232"))
        } else {
            title.text = lastSavedPath?.let { "已保存到 $it" } ?: "全局录制"
            action.text = "开始录制"
            action.setBackgroundColor(Color.parseColor("#2F855A"))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
