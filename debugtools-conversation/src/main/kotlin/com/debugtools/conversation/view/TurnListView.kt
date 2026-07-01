package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.TurnOutcome

@SuppressLint("ViewConstructor")
class TurnListView(
    context: Context,
    turns: List<ConversationTurn>,
    onPick: (ConversationTurn) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        turns.forEach { t ->
            val dur = if (t.endUptimeMs != null) t.endUptimeMs - t.startUptimeMs else null
            val label = t.userInput?.take(20) ?: "(无文本)"
            addView(TextView(context).apply {
                text = "#${t.turnIndex}  $label  ·${if (dur != null) " ${dur}ms" else ""} ${outcomeEmoji(t.outcome)}"
                setTextColor(ConversationColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(ConversationColors.SURFACE) }
                setOnClickListener { onPick(t) }
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * density).toInt() })
        }
    }

    private fun outcomeEmoji(o: TurnOutcome) = when (o) {
        TurnOutcome.SUCCESS -> "✓"
        TurnOutcome.FAILED -> "✗"
        TurnOutcome.TIMEOUT -> "⏱"
        TurnOutcome.ABORTED -> "⊘"
    }
}
