package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Arrays

/**
 * Scrolling dB envelope over a fixed window. Holds the most recent [COLUMNS]
 * per-frame dB values in a ring buffer ([writeIndex] wraps around); onDraw
 * unrolls them oldest->newest left to right. Anomaly columns get a red marker.
 */
@SuppressLint("ViewConstructor")
class ScrollingEnvelopeView(context: Context, lineColor: Int) : View(context) {

    private companion object {
        const val COLUMNS = 160
        const val MIN_DB = -90f
    }

    private val density = resources.displayMetrics.density
    private val db = FloatArray(COLUMNS) { MIN_DB }
    private val flag = BooleanArray(COLUMNS)
    private var writeIndex = 0

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = lineColor
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = (lineColor and 0x00FFFFFF) or (0x4D shl 24) // ~30% alpha
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AudioColors.ANOMALY; strokeWidth = 1.5f * density
    }

    fun pushColumn(dbValue: Float, anomaly: Boolean) {
        db[writeIndex] = dbValue.coerceIn(MIN_DB, 0f)
        flag[writeIndex] = anomaly
        writeIndex = (writeIndex + 1) % COLUMNS
        invalidate()
    }

    /** Flag the most recently written column as anomalous (called after pushColumn). */
    fun markLast() {
        flag[(writeIndex - 1 + COLUMNS) % COLUMNS] = true
        invalidate()
    }

    fun clear() {
        Arrays.fill(db, MIN_DB); Arrays.fill(flag, false); writeIndex = 0; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val path = Path()
        for (k in 0 until COLUMNS) {
            val idx = (writeIndex + k) % COLUMNS // oldest -> newest, left -> right
            val x = w * k / (COLUMNS - 1)
            val norm = (db[idx] - MIN_DB) / (-MIN_DB) // 0..1
            val y = h - norm * h
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fillPath = Path(path).apply { lineTo(w, h); lineTo(0f, h); close() }
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, stroke)
        for (k in 0 until COLUMNS) {
            if (flag[(writeIndex + k) % COLUMNS]) {
                val x = w * k / (COLUMNS - 1)
                canvas.drawLine(x, 0f, x, h, mark)
            }
        }
    }
}
