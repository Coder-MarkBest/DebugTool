package com.debugtools.core.window.view

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

internal class MinimizedView(
    context: Context,
    private val layoutParams: WindowManager.LayoutParams,
    private val onWindowLayoutChanged: () -> Unit
) : FrameLayout(context) {

    var onClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var touchDownParamX = 0f
    private var touchDownParamY = 0f
    private var didDrag = false

    init {
        setBackgroundColor(Color.parseColor("#CC4A90E2"))
        addView(TextView(context).apply {
            text = "🐛"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        setOnLongClickListener { onLongClick?.invoke(); true }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchDownRawX = event.rawX
                touchDownRawY = event.rawY
                touchDownParamX = layoutParams.x.toFloat()
                touchDownParamY = layoutParams.y.toFloat()
                didDrag = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchDownRawX
                val dy = event.rawY - touchDownRawY
                if (!didDrag && (Math.abs(dx) > 10f || Math.abs(dy) > 10f)) {
                    didDrag = true
                }
                if (didDrag) {
                    layoutParams.x = (touchDownParamX + dx).toInt()
                    layoutParams.y = (touchDownParamY + dy).toInt()
                    onWindowLayoutChanged()
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!didDrag) {
                    onClick?.invoke()
                } else {
                    snapToEdge(event.rawX)
                }
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    private fun snapToEdge(currentRawX: Float) {
        val screenWidth = resources.displayMetrics.widthPixels
        // gravity is TOP|START in MINIMIZED mode, so x=0 is left edge,
        // x = screenWidth - width is right edge.
        val targetX = if (currentRawX < screenWidth / 2f) 0 else screenWidth - width
        ObjectAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = 200
            addUpdateListener { anim ->
                layoutParams.x = anim.animatedValue as Int
                try {
                    onWindowLayoutChanged()
                } catch (_: Exception) { /* View may be detached during mode switch */ }
            }
            start()
        }
    }
}
