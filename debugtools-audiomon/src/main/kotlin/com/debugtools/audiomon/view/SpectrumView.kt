package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.pow

/**
 * Frequency spectrum bar chart with logarithmic band mapping.
 *
 * Groups FFT bins into [bandCount] visual bands using a squared mapping
 * (approximating log-scale perception). Bars are drawn bottom-up with
 * rounded corners and frequency labels at key positions.
 */
@SuppressLint("ViewConstructor")
class SpectrumView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#63B3ED")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A0AEC0")
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
    }

    private var magnitudes = FloatArray(0)
    private val bandCount = 32
    private val barRect = RectF()

    /**
     * Update the spectrum display with new FFT magnitudes.
     * @param mags Normalized magnitude array (0..1) from [FftProcessor].
     */
    fun setSpectrum(mags: FloatArray) {
        magnitudes = mags
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (140 * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val labelArea = 16f * density

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        if (magnitudes.isEmpty()) return

        val chartH = h - labelArea
        val totalBins = magnitudes.size
        val barWidth = w / bandCount
        val gap = 2f * density
        val cornerRadius = 3f * density

        for (i in 0 until bandCount) {
            // Logarithmic-ish mapping: squared index → FFT bin range
            val lowBin = (totalBins * (i.toFloat() / bandCount).toDouble().pow(2.0)).toInt()
            val highBin = (totalBins * ((i + 1).toFloat() / bandCount).toDouble().pow(2.0)).toInt()
                .coerceAtMost(totalBins - 1)

            // Average magnitude across the bin range
            var sum = 0f
            var count = 0
            for (b in lowBin..highBin) {
                sum += magnitudes[b]
                count++
            }
            val avgMag = if (count > 0) sum / count else 0f

            val barH = avgMag * chartH * 0.9f
            val left = i * barWidth + gap / 2
            val right = (i + 1) * barWidth - gap / 2
            val top = chartH - barH

            barRect.set(left, top, right, chartH)
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint)
        }

        // Frequency labels at key positions
        val labels = listOf("100", "1k", "4k", "8k")
        val positions = listOf(0.06f, 0.25f, 0.6f, 0.95f)
        for ((label, pos) in labels.zip(positions)) {
            canvas.drawText(label, w * pos, h - 2f * density, labelPaint)
        }
    }
}
