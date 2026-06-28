package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Arrays

/**
 * Real-time frequency bar graph (EQ-style). The current frame's FFT magnitudes
 * are downsampled into [BARS] vertical bars — low frequency on the left, high on
 * the right, bar height = energy. Bars rise instantly and decay slowly so the
 * display is readable instead of flickery. Instantaneous: no time axis, no scroll.
 */
@SuppressLint("ViewConstructor")
class SpectrumBarsView(context: Context, private val barColor: Int) : View(context) {

    private companion object {
        const val BARS = 32
        const val DECAY = 0.86f
    }

    private val density = resources.displayMetrics.density
    private val levels = FloatArray(BARS)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = barColor }

    fun setSpectrum(magnitudes: FloatArray) {
        if (magnitudes.isEmpty()) return
        val per = maxOf(1, magnitudes.size / BARS)
        for (b in 0 until BARS) {
            val start = b * per
            val end = if (b == BARS - 1) magnitudes.size else minOf(magnitudes.size, (b + 1) * per)
            var m = 0f
            var i = start
            while (i < end) { if (magnitudes[i] > m) m = magnitudes[i]; i++ }
            // rise immediately to a louder value, otherwise ease down for readability
            levels[b] = if (m >= levels[b]) m else levels[b] * DECAY
        }
        invalidate()
    }

    fun clear() { Arrays.fill(levels, 0f); invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = 2f * density
        val barW = (w - gap * (BARS - 1)) / BARS
        if (barW <= 0f) return
        val r = RectF()
        val radius = 1.5f * density
        for (b in 0 until BARS) {
            val x = b * (barW + gap)
            val barH = levels[b].coerceIn(0f, 1f) * h
            r.set(x, h - barH, x + barW, h)
            canvas.drawRoundRect(r, radius, radius, fill)
        }
    }
}
