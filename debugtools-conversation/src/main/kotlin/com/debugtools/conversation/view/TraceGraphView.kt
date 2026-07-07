package com.debugtools.conversation.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.debugtools.conversation.trace.TraceCategory
import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.TraceGraphEdge
import com.debugtools.conversation.trace.TraceGraphNode
import kotlin.math.abs

class TraceGraphView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val nodes = mutableListOf<TraceGraphNode>()
    private val edges = mutableListOf<TraceGraphEdge>()
    private val hitRects = mutableListOf<Pair<RectF, TraceGraphNode>>()
    private var selected: TraceGraphNode? = null
    private var onNodeSelected: ((TraceGraphNode) -> Unit)? = null
    private val nodeSpacing get() = p(116)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ConversationColors.EDGE
        strokeWidth = p(2).toFloat()
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ConversationColors.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = p(2).toFloat()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ConversationColors.TEXT
        textSize = p(11).toFloat()
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ConversationColors.TEXT_DIM
        textSize = p(10).toFloat()
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ConversationColors.SURFACE
        style = Paint.Style.FILL
    }

    fun setGraph(
        graphNodes: List<TraceGraphNode>,
        graphEdges: List<TraceGraphEdge>,
        onSelected: (TraceGraphNode) -> Unit
    ) {
        nodes.clear()
        nodes += graphNodes
        edges.clear()
        edges += graphEdges
        selected = graphNodes.firstOrNull()
        onNodeSelected = onSelected
        selected?.let(onSelected)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = if (nodes.isEmpty()) p(220) else p(92) + (nodes.size - 1) * nodeSpacing + p(130)
        val width = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(widthMeasureSpec)
        } else {
            desiredWidth
        }
        val height = if (nodes.isEmpty()) p(72) else p(132)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        hitRects.clear()
        if (nodes.isEmpty()) {
            canvas.drawText("暂无链路节点", p(12).toFloat(), p(32).toFloat(), dimPaint)
            return
        }

        val y = p(42).toFloat()
        nodes.forEachIndexed { index, node ->
            val x = p(52).toFloat() + index * nodeSpacing
            if (index < nodes.lastIndex) {
                val nextX = p(52).toFloat() + (index + 1) * nodeSpacing
                canvas.drawLine(x + p(13), y, nextX - p(13), y, linePaint)
                drawEdgeBadge(canvas, edges.getOrNull(index), (x + nextX) / 2 - p(22), y - p(18))
            }
            nodePaint.color = nodeColor(node)
            canvas.drawCircle(x, y, p(if (node.type == TraceEventType.ERROR) 9 else 7).toFloat(), nodePaint)
            if (selected == node) {
                canvas.drawCircle(x, y, p(12).toFloat(), selectedPaint)
            }
            canvas.drawText(node.label, x - p(20), y + p(34), textPaint)
            canvas.drawText(node.eventName, x - p(20), y + p(51), dimPaint)
            val rect = RectF(x - p(46), 0f, x + p(70), height.toFloat())
            hitRects += rect to node
        }
    }

    private fun drawEdgeBadge(canvas: Canvas, edge: TraceGraphEdge?, x: Float, y: Float) {
        val text = "${edge?.durationMs ?: 0}ms"
        val w = dimPaint.measureText(text) + p(14)
        val rect = RectF(x, y - p(14), x + w, y + p(4))
        canvas.drawRoundRect(rect, p(4).toFloat(), p(4).toFloat(), badgePaint)
        canvas.drawText(text, x + p(7), y - p(1).toFloat(), dimPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val hit = hitRects.firstOrNull { (rect, _) ->
            rect.contains(event.x, event.y) || abs(rect.centerX() - event.x) < p(42)
        }?.second ?: return true
        selected = hit
        onNodeSelected?.invoke(hit)
        invalidate()
        return true
    }

    private fun nodeColor(node: TraceGraphNode): Int = when {
        node.type == TraceEventType.ERROR -> ConversationColors.FAILED
        node.category == TraceCategory.ASR -> 0xFF60A5FA.toInt()
        node.category == TraceCategory.NLU -> 0xFFA78BFA.toInt()
        node.category == TraceCategory.TTS -> 0xFFF59E0B.toInt()
        node.category == TraceCategory.TOOL -> 0xFF34D399.toInt()
        node.category == TraceCategory.VAD -> ConversationColors.ACCENT
        else -> ConversationColors.NEUTRAL
    }

    private fun p(v: Int): Int = (v * density).toInt()
}
