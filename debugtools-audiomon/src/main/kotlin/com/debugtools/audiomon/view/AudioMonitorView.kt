package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.audiomon.anomaly.AnomalyEvent
import com.debugtools.audiomon.anomaly.StreamId
import com.debugtools.audiomon.presenter.AudioView

/**
 * Scrollable audio panel: record controls, two stream lanes (A/B) with scrolling
 * envelope + spectrogram, an accumulating anomaly list, and a collapsible legend.
 */
@SuppressLint("ViewConstructor")
class AudioMonitorView(context: Context) : ScrollView(context), AudioView {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val laneA = StreamLaneView(context, StreamId.A)
    private val laneB = StreamLaneView(context, StreamId.B)
    private val anomalyList = AnomalyListView(context)
    private val legend = AnomalyLegendView(context)
    private val statusText = TextView(context)
    private val inputWarning = TextView(context)
    private val lastSessionText = TextView(context)
    private val toggleBtn = TextView(context)
    private val reportBtn = TextView(context)
    private var toggleListener: (() -> Unit)? = null
    private var reportListener: (() -> Unit)? = null

    private fun mx(v: Float) = (v * density).toInt()

    init {
        setBackgroundColor(AudioColors.BG)

        toggleBtn.apply {
            text = "▶ 开始录制"; setTextColor(AudioColors.TEXT); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, mx(10f), 0, mx(10f))
            background = pill(AudioColors.START)
            setOnClickListener { toggleListener?.invoke() }
        }
        statusText.apply { setTextColor(AudioColors.TEXT_DIM); textSize = 12f; setPadding(mx(2f), mx(8f), mx(2f), mx(8f)) }
        inputWarning.apply {
            setTextColor(AudioColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setPadding(mx(8f), mx(6f), mx(8f), mx(6f))
            background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(AudioColors.STOP) }
            visibility = GONE
        }
        lastSessionText.apply { setTextColor(AudioColors.TEXT_DIM); textSize = 12f }
        reportBtn.apply {
            text = "📤 上报最近会话"; setTextColor(AudioColors.TEXT); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            alpha = 0.4f; isClickable = false; isFocusable = false
            setPadding(0, mx(10f), 0, mx(10f))
            background = pill(AudioColors.REPORT)
            setOnClickListener { reportListener?.invoke() }
        }

        content.setPadding(mx(12f), mx(12f), mx(12f), mx(12f))
        content.addView(toggleBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(inputWarning, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = mx(4f) })
        content.addView(laneA, laneParams())
        content.addView(laneB, laneParams())
        content.addView(sectionLabel("⚠ 异常"))
        content.addView(anomalyList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(lastSessionText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = mx(8f) })
        content.addView(reportBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = mx(4f) })
        content.addView(legend, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        addView(content)
    }

    private fun laneParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = mx(10f) }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 10f * density; setColor(color)
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text; setTextColor(AudioColors.TEXT_DIM); textSize = 11f
        typeface = Typeface.DEFAULT_BOLD; setPadding(0, mx(12f), 0, mx(4f))
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    // --- AudioView ---

    override fun showStatus(text: String) { statusText.text = text }

    override fun showMonitoringState(isRecording: Boolean) {
        toggleBtn.text = if (isRecording) "⏹ 结束录制" else "▶ 开始录制"
        toggleBtn.background = pill(if (isRecording) AudioColors.STOP else AudioColors.START)
    }

    override fun setToggleListener(listener: () -> Unit) { toggleListener = listener }
    override fun setReportListener(listener: () -> Unit) { reportListener = listener }

    override fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean) {
        lastSessionText.text = "最近会话: $sessionId\n$summary" +
            if (!reporterConfigured) "\n(未配置上报接口)" else ""
        reportBtn.alpha = if (reporterConfigured) 1f else 0.4f
        reportBtn.isClickable = reporterConfigured
        reportBtn.isFocusable = reporterConfigured
    }

    override fun clearLive() {
        laneA.clear(); laneB.clear(); anomalyList.clear()
        showInputWarning(null)
    }

    override fun showInputWarning(message: String?) {
        if (message == null) {
            inputWarning.visibility = GONE
        } else {
            inputWarning.text = message
            inputWarning.visibility = VISIBLE
        }
    }

    override fun pushLiveFrame(stream: StreamId, peak: Float, rms: Float, spectrum: FloatArray) {
        (if (stream == StreamId.A) laneA else laneB).pushFrame(peak, rms, spectrum)
    }

    override fun showAnomaly(event: AnomalyEvent) {
        (if (event.stream == StreamId.A) laneA else laneB).markLastAnomaly()
        anomalyList.addEntry(
            "${fmtTime(event.timeMs)} · [${event.stream.label}] ${event.type.label} · ${event.detail}",
            AudioColors.anomalyTypeColor(event.type)
        )
    }
}
