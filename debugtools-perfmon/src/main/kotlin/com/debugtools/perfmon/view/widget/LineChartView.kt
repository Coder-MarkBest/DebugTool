package com.debugtools.perfmon.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Simple multi-series line chart. Caller updates via [setSeries].
 * Auto-scales Y to [0, maxValue * 1.1f].
 */
@SuppressLint("ViewConstructor")
class LineChartView(context: Context) : View(context) {

    data class Series(val label: String, val color: Int, val values: List<Float>)

    private var seriesList: List<Series> = emptyList()
    private var yMax: Float = 100f

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#4A5568"); strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0"); textSize = 26f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
    }

    fun setSeries(series: List<Series>, yAxisMax: Float? = null) {
        seriesList = series
        val maxFromValues = series.flatMap { it.values }.maxOrNull() ?: 100f
        yMax = (yAxisMax ?: (maxFromValues * 1.1f)).coerceAtLeast(1f)
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (180 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val chartLeft = 80f
        val chartTop = 24f
        val chartRight = (width - 16).toFloat()
        val chartBottom = (height - 40).toFloat()
        val chartHeight = chartBottom - chartTop
        val chartWidth = chartRight - chartLeft

        // Y axis labels (0, mid, max)
        canvas.drawText("%.0f".format(yMax), 8f, chartTop + 12f, labelPaint)
        canvas.drawText("%.0f".format(yMax / 2), 8f, chartTop + chartHeight / 2 + 8f, labelPaint)
        canvas.drawText("0", 8f, chartBottom, labelPaint)

        // Grid lines
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, gridPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, gridPaint)
        canvas.drawLine(chartLeft, chartTop + chartHeight / 2, chartRight, chartTop + chartHeight / 2, gridPaint)

        // Lines
        for (series in seriesList) {
            if (series.values.size < 2) continue
            linePaint.color = series.color
            val path = Path()
            val stepX = chartWidth / (series.values.size - 1)
            for ((i, v) in series.values.withIndex()) {
                val x = chartLeft + i * stepX
                val y = chartBottom - (v / yMax) * chartHeight
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        // Legend (top-right)
        var legendY = chartTop + 16f
        for (series in seriesList) {
            labelPaint.color = series.color
            canvas.drawText(series.label, chartRight - 200f, legendY, labelPaint)
            legendY += 36f
        }
        labelPaint.color = Color.parseColor("#E2E8F0")  // reset
    }
}
