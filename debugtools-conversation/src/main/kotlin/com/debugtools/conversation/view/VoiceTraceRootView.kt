package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.conversation.trace.AnalyzedVoiceRequest
import com.debugtools.conversation.trace.TraceIssueSeverity
import com.debugtools.conversation.trace.TraceGraphNode
import com.debugtools.conversation.trace.VoiceTraceAnalyzer
import com.debugtools.conversation.trace.VoiceTraceProfile
import com.debugtools.conversation.trace.VoiceTraceSnapshot

@SuppressLint("ViewConstructor")
class VoiceTraceRootView(
    context: Context,
    private val loadSnapshot: () -> VoiceTraceSnapshot?,
    private val loadProfile: () -> VoiceTraceProfile?
) : ScrollView(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(p(12), p(12), p(12), p(12))
    }
    private var requests: List<AnalyzedVoiceRequest> = emptyList()

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(ConversationColors.BG)
        addView(content)
        reload()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        reload()
    }

    private fun reload() {
        val profile = loadProfile()
        val snapshot = loadSnapshot()
        requests = if (profile == null || snapshot == null) {
            emptyList()
        } else {
            val analyzer = VoiceTraceAnalyzer(profile)
            snapshot.eventsByRequest.map { (requestId, events) -> analyzer.analyze(requestId, events) }
                .sortedByDescending { it.rawEvents.firstOrNull()?.wallTimeMs ?: 0L }
        }
        showList()
    }

    private fun showList() {
        content.removeAllViews()
        content.addView(header("请求链路 · 最近 ${requests.size} 次"))
        if (requests.isEmpty()) {
            content.addView(dim("暂无 requestId 事件"))
            return
        }
        requests.forEach { request ->
            content.addView(row(requestSummary(request)) { showDetail(request) })
        }
    }

    private fun showDetail(request: AnalyzedVoiceRequest) {
        content.removeAllViews()
        content.addView(row("← 返回请求列表") { showList() })
        content.addView(header(request.requestId))
        content.addView(dim("性能耗时 ${request.performanceDurationMs}ms · raw events ${request.rawEvents.size}"))

        content.addView(header("链路耗时图"))
        val detail = dim("")
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(TraceGraphView(context).apply {
                setGraph(request.graphNodes, request.graphEdges) { node ->
                    detail.text = nodeDetail(node)
                }
            })
        })
        content.addView(detail)

        content.addView(header("问题"))
        if (request.issues.isEmpty()) {
            content.addView(dim("无异常"))
        } else {
            request.issues.forEach { issue ->
                val prefix = when (issue.severity) {
                    TraceIssueSeverity.CRITICAL -> "[critical]"
                    TraceIssueSeverity.WARNING -> "[warning]"
                    TraceIssueSeverity.INFO -> "[info]"
                }
                content.addView(dim("$prefix ${issue.type} ${issue.detail}"))
            }
        }

        content.addView(header("Raw events"))
        request.rawEvents.forEach { event ->
            content.addView(dim("${event.timestampUptimeMs} · ${event.type.name} · ${event.name}"))
        }
    }

    private fun nodeDetail(node: TraceGraphNode): String {
        val attrs = if (node.attributes.isEmpty()) {
            "attributes: 无"
        } else {
            node.attributes.entries.joinToString("\n") { (key, value) -> "$key = $value" }
        }
        return buildString {
            append(node.label)
            append(" · ")
            append(node.eventName)
            append(" · ")
            append(node.timestampUptimeMs)
            append("ms")
            append("\n")
            append("type = ").append(node.type.name)
            append(" · category = ").append(node.category.name)
            node.ruleId?.let { append(" · rule = ").append(it) }
            append("\n")
            append(attrs)
        }
    }

    private fun requestSummary(request: AnalyzedVoiceRequest): String {
        val critical = request.issues.count { it.severity == TraceIssueSeverity.CRITICAL }
        val warning = request.issues.count { it.severity == TraceIssueSeverity.WARNING }
        val issueText = when {
            critical > 0 -> " · $critical critical"
            warning > 0 -> " · $warning warning"
            else -> ""
        }
        return "${request.requestId} · ${request.timelineItems.size}项 · ${request.performanceDurationMs}ms$issueText"
    }

    private fun header(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(ConversationColors.TEXT)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }

    private fun dim(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(ConversationColors.TEXT_DIM)
        textSize = 11f
        setPadding(0, p(2), 0, p(2))
    }

    private fun row(text: String, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        setTextColor(ConversationColors.TEXT)
        textSize = 12f
        setPadding(p(8), p(8), p(8), p(8))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(6) }
    }
}
