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

/** One stream's lane: chip label + scrolling envelope + spectrogram, in a card. */
@SuppressLint("ViewConstructor")
class StreamLaneView(context: Context, stream: StreamId) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val accent = if (stream == StreamId.A) AudioColors.STREAM_A else AudioColors.STREAM_B
    private val envelope = ScrollingEnvelopeView(context, accent)
    private val spectro = SpectrogramView(context)

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
        addView(label("能量包络"))
        addView(envelope, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
        addView(label("声谱图"))
        addView(spectro, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
    }

    fun pushFrame(db: Float, spectrum: FloatArray) {
        envelope.pushColumn(db, false)
        if (spectrum.isNotEmpty()) spectro.pushColumn(spectrum, false)
    }

    fun markLastAnomaly() {
        envelope.markLast(); spectro.markLast()
    }

    fun clear() { envelope.clear(); spectro.clear() }

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
