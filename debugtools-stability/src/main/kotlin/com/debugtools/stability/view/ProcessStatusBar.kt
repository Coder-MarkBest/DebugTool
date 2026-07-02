package com.debugtools.stability.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("ViewConstructor")
class ProcessStatusBar(
    context: Context,
    status: Map<String, Boolean>
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        if (status.isEmpty()) {
            addView(row("(未配置监控进程)", StabilityColors.TEXT_DIM))
        } else {
            status.forEach { (name, alive) ->
                val color = if (alive) StabilityColors.ALIVE else StabilityColors.DEAD
                val label = if (alive) "🟢 $name 正常" else "🔴 $name 异常"
                addView(row(label, color))
            }
        }
    }

    private fun row(text: String, color: Int) = TextView(context).apply {
        this.text = text; setTextColor(color); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
    }
}
