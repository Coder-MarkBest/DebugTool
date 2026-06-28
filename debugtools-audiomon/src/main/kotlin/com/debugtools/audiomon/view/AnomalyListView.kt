package com.debugtools.audiomon.view

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

/** Accumulating anomaly log (newest on top), capped to [MAX] rows. */
class AnomalyListView(context: Context) : LinearLayout(context) {

    private companion object { const val MAX = 50 }

    private val density = resources.displayMetrics.density

    init { orientation = VERTICAL }

    /** @param message preformatted "m:ss · [B] 削波 · peak 0.99"; dotColor per anomaly type. */
    fun addEntry(message: String, dotColor: Int) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }
        row.addView(TextView(context).apply { text = "● "; setTextColor(dotColor); textSize = 11f })
        row.addView(TextView(context).apply {
            text = message; setTextColor(AudioColors.TEXT); textSize = 11f; typeface = Typeface.MONOSPACE
        })
        addView(row, 0) // newest on top
        if (childCount > MAX) removeViewAt(childCount - 1)
    }

    fun clear() { removeAllViews() }
}
