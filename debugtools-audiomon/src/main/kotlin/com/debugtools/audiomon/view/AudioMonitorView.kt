package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.audiomon.presenter.AudioView

/**
 * Container view that composes a start/stop toggle, [WaveformView],
 * and [SpectrumView] vertically. Implements the [AudioView] MVP interface.
 */
@SuppressLint("ViewConstructor")
class AudioMonitorView(context: Context) : LinearLayout(context), AudioView {

    private val density = resources.displayMetrics.density
    private val waveformView = WaveformView(context)
    private val spectrumView = SpectrumView(context)
    private val statusText: TextView
    private val toggleBtn: TextView
    private var toggleListener: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#1A1A2E"))

        // Start/Stop toggle button
        toggleBtn = TextView(context).apply {
            text = "▶ 开始录音"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor("#2D3748"))
                setStroke((1 * density).toInt(), Color.parseColor("#4A5568"))
            }
            background = bg

            setOnClickListener { toggleListener?.invoke() }
        }
        addView(toggleBtn, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            setMargins(
                (16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt()
            )
        })

        addSectionLabel("振幅波形")
        addView(waveformView, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        addSectionLabel("频谱分析")
        addView(spectrumView, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        statusText = TextView(context).apply {
            setTextColor(Color.parseColor("#A0AEC0"))
            textSize = 12f
            setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                (8 * density).toInt()
            )
        }
        addView(statusText, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun addSectionLabel(text: String) {
        addView(TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#A0AEC0"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                0,
                (4 * density).toInt()
            )
        }, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // --- AudioView implementation ---

    override fun showWaveform(samples: FloatArray, rms: Float) =
        waveformView.setWaveform(samples, rms)

    override fun showSpectrum(magnitudes: FloatArray) =
        spectrumView.setSpectrum(magnitudes)

    override fun showStatus(text: String) {
        statusText.text = text
    }

    override fun showMonitoringState(isMonitoring: Boolean) {
        if (isMonitoring) {
            toggleBtn.text = "⏹ 停止录音"
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor("#C53030"))
                setStroke((1 * density).toInt(), Color.parseColor("#FC8181"))
            }
            toggleBtn.background = bg
        } else {
            toggleBtn.text = "▶ 开始录音"
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * density
                setColor(Color.parseColor("#2D3748"))
                setStroke((1 * density).toInt(), Color.parseColor("#4A5568"))
            }
            toggleBtn.background = bg
        }
    }

    override fun setToggleListener(listener: () -> Unit) {
        toggleListener = listener
    }
}
