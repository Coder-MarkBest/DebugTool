package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Arrays

/**
 * Adobe-Audition-style scrolling mirrored waveform. Each time column stores the
 * frame's peak and RMS amplitude (0..1) in a ring buffer; drawn symmetric about
 * a horizontal center line — a translucent outer **peak** envelope with a solid
 * inner **RMS** body (the "outer-light / inner-solid" Audition look). The ring
 * buffer ([writeIndex] wraps) scrolls oldest->newest left to right; anomaly
 * columns get a red vertical marker.
 */
@SuppressLint("ViewConstructor")
class WaveformView(context: Context, accent: Int) : View(context) {

    private companion object {
        const val COLUMNS = 160
    }

    private val density = resources.displayMetrics.density
    private val peak = FloatArray(COLUMNS)
    private val rms = FloatArray(COLUMNS)
    private val flag = BooleanArray(COLUMNS)
    private var writeIndex = 0

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = (accent and 0x00FFFFFF) or (0x66 shl 24) // ~40% alpha
    }
    private val rmsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = accent
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AudioColors.TEXT_DIM; strokeWidth = 1f; alpha = 80
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AudioColors.ANOMALY; strokeWidth = 1.5f * density
    }

    fun pushColumn(peakValue: Float, rmsValue: Float, anomaly: Boolean) {
        peak[writeIndex] = peakValue.coerceIn(0f, 1f)
        rms[writeIndex] = rmsValue.coerceIn(0f, 1f)
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
        Arrays.fill(peak, 0f); Arrays.fill(rms, 0f); Arrays.fill(flag, false)
        writeIndex = 0; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (72 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val mid = h / 2f
        canvas.drawLine(0f, mid, w, mid, centerPaint)
        drawEnvelope(canvas, peak, mid, peakPaint, w)
        drawEnvelope(canvas, rms, mid, rmsPaint, w)
        for (k in 0 until COLUMNS) {
            if (flag[(writeIndex + k) % COLUMNS]) {
                val x = w * k / (COLUMNS - 1)
                canvas.drawLine(x, 0f, x, h, mark)
            }
        }
    }

    /** Fill a mirrored envelope: top edge left->right, then bottom edge right->left. */
    private fun drawEnvelope(canvas: Canvas, data: FloatArray, mid: Float, paint: Paint, w: Float) {
        val path = Path()
        for (k in 0 until COLUMNS) {
            val idx = (writeIndex + k) % COLUMNS
            val x = w * k / (COLUMNS - 1)
            path.let { if (k == 0) it.moveTo(x, mid - data[idx] * mid) else it.lineTo(x, mid - data[idx] * mid) }
        }
        for (k in COLUMNS - 1 downTo 0) {
            val idx = (writeIndex + k) % COLUMNS
            val x = w * k / (COLUMNS - 1)
            path.lineTo(x, mid + data[idx] * mid)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
