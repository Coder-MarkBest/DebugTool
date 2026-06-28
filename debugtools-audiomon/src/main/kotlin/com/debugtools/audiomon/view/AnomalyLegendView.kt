package com.debugtools.audiomon.view

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.audiomon.anomaly.AnomalyType

/** Bottom collapsible legend: tap header to expand the four anomaly explanations. */
class AnomalyLegendView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val header: TextView
    private val body: LinearLayout
    private var expanded = false

    init {
        orientation = VERTICAL
        header = TextView(context).apply {
            text = "▸ 异常类型说明"
            setTextColor(AudioColors.TEXT_DIM)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
            setOnClickListener { toggle() }
        }
        body = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        for (t in AnomalyType.values()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
            row.addView(TextView(context).apply {
                text = "● "; setTextColor(AudioColors.anomalyTypeColor(t)); textSize = 12f
            })
            row.addView(TextView(context).apply {
                text = "${t.label}: ${t.hint}"; setTextColor(AudioColors.TEXT_DIM); textSize = 11f
            })
            body.addView(row)
        }
        addView(header)
        addView(body)
    }

    private fun toggle() {
        expanded = !expanded
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        header.text = (if (expanded) "▾" else "▸") + " 异常类型说明"
    }
}
