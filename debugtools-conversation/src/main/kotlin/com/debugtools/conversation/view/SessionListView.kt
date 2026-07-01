package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.TurnOutcome

@SuppressLint("ViewConstructor")
class SessionListView(
    context: Context,
    sessions: List<ConversationSession>,
    onPick: (ConversationSession) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        if (sessions.isEmpty()) {
            addView(TextView(context).apply {
                text = "暂无对话记录。请调用 ConversationTracer 上报。"
                setTextColor(ConversationColors.TEXT_DIM); textSize = 12f
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            })
        }
        sessions.forEach { s ->
            val ok = s.turns.count { it.outcome == TurnOutcome.SUCCESS }
            val fail = s.turns.size - ok
            addView(TextView(context).apply {
                text = buildString {
                    append("对话 · ${s.turns.size}轮 · ✓$ok ✗$fail")
                    if (s.endedAtWallMs == null) append(" · (进行中)")
                }
                setTextColor(ConversationColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(ConversationColors.SURFACE) }
                setOnClickListener { onPick(s) }
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * density).toInt() })
        }
    }
}
