package com.debugtools.okhttp.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A collapsible header-list view: shows a single line "Headers (N)" with a chevron;
 * tapping expands to show all key/value pairs.
 */
@SuppressLint("ViewConstructor")
class HeaderFoldView(
    context: Context,
    private val title: String,
    private val headers: List<Pair<String, String>>
) : LinearLayout(context) {

    private var expanded = false
    private val titleView: TextView
    private val body: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(16, 8, 16, 8)

        titleView = TextView(context).apply {
            text = "$title (${headers.size})  ▶"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2D3748"))
            setPadding(16, 12, 16, 12)
            setOnClickListener { toggle() }
        }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        body = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
            setPadding(16, 8, 16, 8)
        }
        headers.forEach { (name, value) ->
            body.addView(TextView(context).apply {
                text = "$name: $value"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 12f
                setPadding(0, 4, 0, 4)
                gravity = Gravity.START
            })
        }
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun toggle() {
        expanded = !expanded
        body.visibility = if (expanded) VISIBLE else GONE
        titleView.text = "$title (${headers.size})  ${if (expanded) "▼" else "▶"}"
    }
}
