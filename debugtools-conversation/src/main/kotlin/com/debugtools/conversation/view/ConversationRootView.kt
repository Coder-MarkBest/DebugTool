package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.conversation.analyzer.TurnAnalyzer
import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus

/**
 * Three-layer navigation: session list → turn list → turn detail (timeline + stage info + diagnostics).
 *
 * [loadSessions] is invoked on construction AND onAttachedToWindow so newly written
 * sessions appear when the tab is re-opened.
 */
@SuppressLint("ViewConstructor")
class ConversationRootView(
    context: Context,
    private val loadSessions: () -> List<ConversationSession>
) : ScrollView(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(12), p(12), p(12)) }
    private var sessions: List<ConversationSession> = emptyList()

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(ConversationColors.BG)
        addView(content)
        sessions = loadSessions()
        showList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sessions = loadSessions()
        showList()
    }

    // ── L1: session list ──

    private fun showList() {
        content.removeAllViews()
        content.addView(header("对话链路 · 最近 ${sessions.size} 次"))
        content.addView(SessionListView(context, sessions) { showTurns(it) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ── L2: turn list ──

    private fun showTurns(session: ConversationSession) {
        content.removeAllViews()
        content.addView(backBtn { showList() })
        content.addView(header("${session.turns.size} 轮 · ${session.metadata?.get("scene")?.let { "$it" } ?: ""}"))
        content.addView(TurnListView(context, session.turns) { showDetail(it) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ── L3: turn detail ──

    private fun showDetail(turn: ConversationTurn) {
        content.removeAllViews()
        content.addView(backBtn { showTurns(sessions.first { it.turns.any { t -> t.turnId == turn.turnId } }) })
        val dur = if (turn.endUptimeMs != null) "${turn.endUptimeMs - turn.startUptimeMs}ms" else "进行中"
        content.addView(header("#${turn.turnIndex}  ${turn.userInput?.take(30) ?: "(无文本)"}  ·$dur"))
        content.addView(TurnDetailView(context, turn), lp())

        // stage info rows
        content.addView(header("阶段详情"))
        turn.stages.forEach { s ->
            val sb = StringBuilder("${s.name} [${statusLabel(s.status)}] ${s.startOffsetMs}-${s.endOffsetMs ?: "?"}ms")
            if (!s.input.isNullOrBlank()) sb.append("\n  ← ${s.input.take(60)}")
            if (!s.output.isNullOrBlank()) sb.append("\n  → ${s.output.take(60)}")
            if (!s.error.isNullOrBlank()) sb.append("\n  ⚠ ${s.error}")
            content.addView(dim(sb.toString()))
        }

        // diagnostics
        content.addView(header("⚠ 诊断"))
        val issues = TurnAnalyzer.analyze(turn)
        if (issues.isEmpty()) content.addView(dim("无异常"))
        else issues.forEach { content.addView(dim("• [${it.type}] ${it.stageName ?: ""} ${it.detail}")) }
    }

    private fun statusLabel(s: StageStatus) = when (s) {
        StageStatus.SUCCESS -> "✓"
        StageStatus.FAILED -> "✗"
        StageStatus.RUNNING -> "…"
        StageStatus.SKIPPED -> "-"
    }

    // ── helpers ──

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) }

    private fun header(t: String) = TextView(context).apply {
        text = t; setTextColor(ConversationColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }

    private fun dim(t: String) = TextView(context).apply {
        text = t; setTextColor(ConversationColors.TEXT_DIM); textSize = 11f; setPadding(0, p(2), 0, p(2))
    }

    private fun backBtn(onClick: () -> Unit) = TextView(context).apply {
        text = "← 返回"; setTextColor(ConversationColors.ACCENT); textSize = 12f; setPadding(0, p(4), 0, p(8))
        setOnClickListener { onClick() }
    }
}
