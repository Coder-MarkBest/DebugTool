package com.debugtools.audiomon.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import java.util.Arrays

/**
 * Scrolling spectrogram. A [COLUMNS] x [BINS] bitmap is used as a ring buffer of
 * columns: each pushColumn writes one column at [writeIndex] (wraps); onDraw
 * blits the bitmap in two halves so the oldest column is leftmost. Low
 * frequencies at the bottom. Anomaly columns get a top tick.
 */
class SpectrogramView(context: Context) : View(context) {

    private companion object {
        const val COLUMNS = 160
        const val BINS = 64
    }

    private val density = resources.displayMetrics.density
    private val bitmap = Bitmap.createBitmap(COLUMNS, BINS, Bitmap.Config.ARGB_8888)
    private val column = IntArray(BINS)
    private val flag = BooleanArray(COLUMNS)
    private var writeIndex = 0
    private val paint = Paint()
    private val mark = Paint().apply { color = AudioColors.ANOMALY; strokeWidth = 2f * density }

    init { bitmap.eraseColor(AudioColors.BG) }

    /** @param magnitudes normalized 0..1, length fftSize/2; empty => skip. */
    fun pushColumn(magnitudes: FloatArray, anomaly: Boolean) {
        if (magnitudes.isEmpty()) return
        val per = maxOf(1, magnitudes.size / BINS)
        for (b in 0 until BINS) {
            val start = b * per
            val end = if (b == BINS - 1) magnitudes.size else minOf(magnitudes.size, (b + 1) * per)
            var sum = 0f; var cnt = 0
            var i = start
            while (i < end) { sum += magnitudes[i]; cnt++; i++ }
            val level = if (cnt > 0) sum / cnt else 0f
            column[BINS - 1 - b] = AudioColors.spectrogramColor(level) // low freq at bottom
        }
        bitmap.setPixels(column, 0, 1, writeIndex, 0, 1, BINS)
        flag[writeIndex] = anomaly
        writeIndex = (writeIndex + 1) % COLUMNS
        invalidate()
    }

    fun markLast() {
        flag[(writeIndex - 1 + COLUMNS) % COLUMNS] = true
        invalidate()
    }

    fun clear() {
        bitmap.eraseColor(AudioColors.BG); Arrays.fill(flag, false); writeIndex = 0; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val leftCount = COLUMNS - writeIndex // columns [writeIndex, COLUMNS) are oldest
        val split = (w.toFloat() * leftCount / COLUMNS).toInt()
        if (leftCount > 0) {
            canvas.drawBitmap(bitmap, Rect(writeIndex, 0, COLUMNS, BINS), Rect(0, 0, split, h), paint)
        }
        if (writeIndex > 0) {
            canvas.drawBitmap(bitmap, Rect(0, 0, writeIndex, BINS), Rect(split, 0, w, h), paint)
        }
        for (k in 0 until COLUMNS) {
            if (flag[(writeIndex + k) % COLUMNS]) {
                val x = w.toFloat() * k / COLUMNS
                canvas.drawLine(x, 0f, x, 4f * density, mark)
            }
        }
    }
}
