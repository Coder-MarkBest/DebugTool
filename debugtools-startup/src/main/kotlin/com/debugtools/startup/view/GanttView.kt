package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.debugtools.startup.protocol.StartupSession

/** Time-axis Gantt: one row per step, bar start->end colored by status; critical path bars outlined. */
@SuppressLint("ViewConstructor")
class GanttView(context: Context, private val session: StartupSession, private val critical: Set<String>) : View(context) {

    private val density = resources.displayMetrics.density
    private val rowH = 26f * density
    private val labelW = 90f * density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = StartupColors.CRITICAL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StartupColors.TEXT; textSize = 11f * density }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StartupColors.TEXT_DIM; textSize = 9f * density }

    private val t0 = session.launchUptimeMs
    private val totalMs = ((session.completedUptimeMs ?: session.steps.mapNotNull { it.endUptimeMs }.maxOrNull()
        ?: session.launchUptimeMs) - t0).coerceAtLeast(1L)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (session.steps.size * rowH + 22f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val chartW = width - labelW
        if (chartW <= 0) return
        fun x(ms: Long) = labelW + chartW * (ms - t0).coerceIn(0, totalMs) / totalMs.toFloat()

        session.steps.forEachIndexed { i, s ->
            val top = i * rowH + 2f * density
            canvas.drawText(s.name.take(10), 0f, top + rowH * 0.6f, label)
            val end = s.endUptimeMs ?: (t0 + totalMs)
            val r = RectF(x(s.startUptimeMs), top, x(end).coerceAtLeast(x(s.startUptimeMs) + 2f), top + rowH - 6f * density)
            bar.color = StartupColors.statusColor(s.status)
            canvas.drawRoundRect(r, 3f * density, 3f * density, bar)
            if (s.name in critical) canvas.drawRoundRect(r, 3f * density, 3f * density, outline)
        }
        val baseY = session.steps.size * rowH + 14f * density
        canvas.drawText("0ms", labelW, baseY, axis)
        canvas.drawText("${totalMs}ms", (width - 40f * density), baseY, axis)
    }
}
