package com.debugtools.perfmon.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.debugtools.perfmon.data.ThreadState

/**
 * Horizontal stacked bar showing thread-state distribution (R/S/D/Z/T) with counts.
 */
@SuppressLint("ViewConstructor")
class ThreadStateView(context: Context) : View(context) {

    private var distribution: Map<ThreadState, Int> = emptyMap()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f
    }

    fun setDistribution(d: Map<ThreadState, Int>) {
        distribution = d
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (96 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val total = distribution.values.sum().coerceAtLeast(1)
        val barTop = 24f
        val barBottom = (height - 24).toFloat()
        val barLeft = 16f
        val barRight = (width - 16).toFloat()

        var cursorX = barLeft
        val order = listOf(
            ThreadState.RUNNING to Color.parseColor("#68D391"),
            ThreadState.SLEEPING to Color.parseColor("#63B3ED"),
            ThreadState.DISK_WAIT to Color.parseColor("#FBD38D"),
            ThreadState.ZOMBIE to Color.parseColor("#FC8181"),
            ThreadState.STOPPED to Color.parseColor("#A0AEC0"),
            ThreadState.UNKNOWN to Color.parseColor("#718096")
        )
        for ((state, color) in order) {
            val count = distribution[state] ?: 0
            if (count == 0) continue
            val width = (count.toFloat() / total) * (barRight - barLeft)
            barPaint.color = color
            canvas.drawRect(cursorX, barTop, cursorX + width, barBottom, barPaint)
            canvas.drawText("${state.name.first()} $count", cursorX + 8f, barBottom - 12f, labelPaint)
            cursorX += width
        }
    }
}
