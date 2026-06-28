package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StepStatus

/** Vertical list of sessions; tap a row -> onPick. */
@SuppressLint("ViewConstructor")
class SessionListView(
    context: Context,
    sessions: List<StartupSession>,
    onPick: (StartupSession) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        if (sessions.isEmpty()) {
            addView(TextView(context).apply {
                text = "暂无启动记录。请在宿主 Application.onCreate 调用 AppStartupMonitor 上报。"
                setTextColor(StartupColors.TEXT_DIM); textSize = 12f
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            })
        }
        sessions.forEach { s ->
            val ok = s.steps.count { it.status == StepStatus.SUCCESS }
            val fail = s.steps.count { it.status == StepStatus.FAILED }
            val totalMs = (s.completedUptimeMs ?: s.steps.mapNotNull { it.endUptimeMs }.maxOrNull() ?: s.launchUptimeMs) - s.launchUptimeMs
            addView(TextView(context).apply {
                text = "启动 @${s.startedAtWallMs} · ${totalMs}ms · ✓$ok ✗$fail" +
                    if (!s.completedExplicitly) " · (未显式完成)" else ""
                setTextColor(StartupColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StartupColors.SURFACE) }
                setOnClickListener { onPick(s) }
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * density).toInt() })
        }
    }
}
