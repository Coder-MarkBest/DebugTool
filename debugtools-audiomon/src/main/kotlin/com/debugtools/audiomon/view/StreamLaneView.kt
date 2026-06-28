package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.audiomon.anomaly.StreamId

/** One stream's lane: chip label + Audition-style waveform + real-time spectrum bars, in a card. */
@SuppressLint("ViewConstructor")
class StreamLaneView(context: Context, stream: StreamId) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val accent = if (stream == StreamId.A) AudioColors.STREAM_A else AudioColors.STREAM_B
    private val waveform = WaveformView(context, accent)
    private val bars = SpectrumBarsView(context, accent)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(AudioColors.SURFACE)
            cornerRadius = 12f * density
            setStroke((1.5f * density).toInt(), accent)
        }
        val pad = (10 * density).toInt()
        setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())

        val subtitle = if (stream == StreamId.A) "A路 · 处理后" else "B路 · 麦克风"
        addView(chip(subtitle), LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(label("波形"))
        addView(waveform, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
        addView(label("频谱"))
        addView(bars, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
    }

    fun pushFrame(peak: Float, rms: Float, spectrum: FloatArray) {
        waveform.pushColumn(peak, rms, false)
        if (spectrum.isNotEmpty()) bars.setSpectrum(spectrum)
    }

    /** Bars have no time axis, so only the waveform carries the anomaly marker. */
    fun markLastAnomaly() {
        waveform.markLast()
    }

    fun clear() { waveform.clear(); bars.clear() }

    private fun chip(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(accent)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        val h = (3 * density).toInt(); val w = (8 * density).toInt()
        setPadding(w, h, w, h)
        background = GradientDrawable().apply {
            cornerRadius = 20f * density
            setStroke((1 * density).toInt(), accent)
        }
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(AudioColors.TEXT_DIM)
        textSize = 10f
        setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
    }
}
