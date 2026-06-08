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
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : FrameLayout(context) {

    var onClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var touchDownParamX = 0f
    private var touchDownParamY = 0f

    init {
        val sizePx = (56 * resources.displayMetrics.density).toInt()
        layoutParams.width = sizePx
        layoutParams.height = sizePx
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
                true
            }
            MotionEvent.ACTION_MOVE -> {
                layoutParams.x = (touchDownParamX + event.rawX - touchDownRawX).toInt()
                layoutParams.y = (touchDownParamY + event.rawY - touchDownRawY).toInt()
                windowManager.updateViewLayout(this, layoutParams)
                true
            }
            MotionEvent.ACTION_UP -> {
                val movedX = Math.abs(event.rawX - touchDownRawX)
                val movedY = Math.abs(event.rawY - touchDownRawY)
                if (movedX < 10f && movedY < 10f) {
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
        val targetX = if (currentRawX < screenWidth / 2f) 0 else screenWidth - layoutParams.width
        ObjectAnimator.ofInt(layoutParams.x, targetX).apply {
            duration = 200
            addUpdateListener { anim ->
                layoutParams.x = anim.animatedValue as Int
                windowManager.updateViewLayout(this@MinimizedView, layoutParams)
            }
            start()
        }
    }
}
