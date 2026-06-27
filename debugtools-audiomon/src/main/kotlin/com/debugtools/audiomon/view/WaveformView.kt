package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Oscilloscope-style waveform display showing actual PCM oscillations.
 *
 * Displays a fixed window of raw samples centered on a dashed midline,
 * with auto-gain so quiet signals are still visible.
 */
@SuppressLint("ViewConstructor")
class WaveformView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A5568")
        strokeWidth = 1f * density
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#63B3ED")
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A5568")
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(
            floatArrayOf(8f * density, 4f * density), 0f
        )
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3363B3ED")
        strokeWidth = 6f * density
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private var samples = FloatArray(0)
    private var rms = 0f
    private val path = Path()
    private val glowPath = Path()

    /** Number of PCM samples to display across the view width */
    private val displayWindow = 300

    /**
     * Push a new window of raw PCM samples.
     * @param samples Normalized PCM values (-1..1), most recent at the end.
     * @param rms Current RMS level for auto-gain and level indicator.
     */
    fun setWaveform(samples: FloatArray, rms: Float) {
        this.samples = samples
        this.rms = rms
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (120 * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Center line (dashed)
        canvas.drawLine(0f, cy, w, cy, centerLinePaint)

        // Grid lines at ±50%
        canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, gridPaint)
        canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, gridPaint)

        if (samples.isEmpty()) return

        // Auto-gain: amplify quiet signals so they're visible
        // When rms is very small, boost gain; when loud, keep at 1x
        val gain = if (rms < 0.01f) 10f
        else if (rms < 0.1f) (1f / rms).coerceAtMost(8f)
        else 1f

        val usableSamples = samples.takeLast(displayWindow)
        val n = usableSamples.size
        if (n < 2) return

        // Build waveform path — oscillates above and below center
        path.reset()
        glowPath.reset()
        val stepX = w / (n - 1)

        for (i in 0 until n) {
            val x = i * stepX
            val s = usableSamples[i] * gain
            val y = cy - s * cy * 0.85f
            if (i == 0) {
                path.moveTo(x, y)
                glowPath.moveTo(x, y)
            } else {
                path.lineTo(x, y)
                glowPath.lineTo(x, y)
            }
        }

        // Glow layer (wider, translucent)
        canvas.drawPath(glowPath, glowPaint)
        // Sharp waveform on top
        canvas.drawPath(path, wavePaint)

        // RMS level indicator (small text in top-right)
        val dbText = if (rms > 0.001f) {
            val db = 20f * Math.log10(rms.toDouble() + 1e-10).toFloat()
            "%.1f dB".format(db)
        } else {
            "-∞ dB"
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#A0AEC0")
            textSize = 10f * density
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(dbText, w - 4f * density, 12f * density, labelPaint)
    }
}
