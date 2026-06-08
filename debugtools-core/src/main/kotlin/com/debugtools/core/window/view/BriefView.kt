package com.debugtools.core.window.view

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.window.BriefOrientation

internal class BriefView(
    context: Context,
    orientation: BriefOrientation
) : LinearLayout(context) {

    var onClick: (() -> Unit)? = null

    init {
        this.orientation = if (orientation == BriefOrientation.VERTICAL) VERTICAL else HORIZONTAL
        setBackgroundColor(Color.parseColor("#CC1A1A2E"))
        setPadding(8, 8, 8, 8)
        setOnClickListener { onClick?.invoke() }
    }

    fun update(items: List<BriefItem>) {
        removeAllViews()
        items.forEach { item ->
            addView(TextView(context).apply {
                text = item.text
                setTextColor(item.color ?: Color.WHITE)
                textSize = 10f
                setPadding(4, 4, 4, 4)
            })
        }
    }
}
