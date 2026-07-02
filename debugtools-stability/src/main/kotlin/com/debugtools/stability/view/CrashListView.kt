package com.debugtools.stability.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.stability.protocol.CrashEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("ViewConstructor")
class CrashListView(
    context: Context,
    entries: List<CrashEntry>
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    init {
        orientation = VERTICAL
        if (entries.isEmpty()) {
            addView(TextView(context).apply {
                text = "暂无崩溃记录"
                setTextColor(StabilityColors.TEXT_DIM); textSize = 12f
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            })
        }
        entries.forEach { e ->
            val collapsed = "${timeFormat.format(Date(e.timestamp))}  ${StabilityColors.crashEmoji(e.type)}  ${e.processName}  ${e.sourcePath}"
            val expanded  = "${timeFormat.format(Date(e.timestamp))}  ${StabilityColors.crashEmoji(e.type)}  ${e.processName}\n${e.sourcePath}\n\n${e.stackTrace.take(2000)}"
            val row = TextView(context).apply {
                text = collapsed
                setTextColor(StabilityColors.TEXT); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StabilityColors.SURFACE) }
                setOnClickListener {
                    if (tag == "expanded") {
                        text = collapsed
                        tag = null
                    } else {
                        text = expanded
                        tag = "expanded"
                    }
                }
            }
            addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (4 * density).toInt() })
        }
    }
}
