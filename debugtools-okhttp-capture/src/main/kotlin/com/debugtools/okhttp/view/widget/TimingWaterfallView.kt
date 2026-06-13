package com.debugtools.okhttp.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.debugtools.okhttp.data.Timing

/**
 * Renders an HTTP request's [Timing] as a horizontal waterfall.
 *
 * Layout (top → bottom):
 *   DNS       █████ 12ms
 *   Connect       ██████ 18ms
 *   TLS                  ████████ 45ms
 *   Send                          █ 3ms
 *   Wait                           ████████████ 47ms
 *   Receive                                    █ 2ms
 *
 * Bars start at the cumulative time so far. If a phase value is null, the row is
 * skipped (e.g. no TLS for HTTP).
 */
@SuppressLint("ViewConstructor")
class TimingWaterfallView(
    context: Context,
    private val timing: Timing
) : View(context) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#63B3ED") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }

    private data class Phase(val label: String, val durationMs: Long?)

    private val phases: List<Phase> = listOf(
        Phase("DNS", timing.dnsMs),
        Phase("Connect", timing.connectMs),
        Phase("TLS", timing.tlsMs),
        Phase("Send", timing.requestSendMs),
        Phase("Wait", timing.waitMs),
        Phase("Receive", timing.responseReceiveMs)
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = phases.count { it.durationMs != null } + 1  // +1 for total row
        val rowHeightPx = (48 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, rows * rowHeightPx + 16)
    }

    override fun onDraw(canvas: Canvas) {
        val totalMs = timing.totalMs.coerceAtLeast(1)
        val rowHeightPx = (48 * resources.displayMetrics.density).toInt()
        val labelWidth = (96 * resources.displayMetrics.density).toInt()
        val chartLeft = labelWidth
        val chartRight = width - 16
        val chartWidth = chartRight - chartLeft

        var cumMs = 0L
        var rowIndex = 0
        for (phase in phases) {
            val ms = phase.durationMs ?: continue
            val y = rowIndex * rowHeightPx + 8
            // Label
            canvas.drawText(phase.label, 8f, (y + rowHeightPx * 0.65f), labelPaint)
            // Bar
            val startX = chartLeft + (chartWidth * cumMs.toFloat() / totalMs)
            val endX = chartLeft + (chartWidth * (cumMs + ms).toFloat() / totalMs)
            canvas.drawRect(startX, (y + 8).toFloat(), endX, (y + rowHeightPx - 8).toFloat(), barPaint)
            // ms label
            canvas.drawText("${ms}ms", endX + 8f, (y + rowHeightPx * 0.65f), labelPaint)
            cumMs += ms
            rowIndex++
        }
        // Total row
        val y = rowIndex * rowHeightPx + 8
        canvas.drawText("Total ${timing.totalMs}ms", 8f, (y + rowHeightPx * 0.65f), labelPaint)
    }
}
