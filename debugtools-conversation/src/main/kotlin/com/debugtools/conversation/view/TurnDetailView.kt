package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.debugtools.conversation.protocol.ConversationTurn

/**
 * Top section: stage timeline (simplified Gantt).
 * Paints one colored bar per stage, left-to-right by offset, plus the stage name label.
 */
@SuppressLint("ViewConstructor")
class TurnDetailView(context: Context, private val turn: ConversationTurn) : View(context) {

    private val density = resources.displayMetrics.density
    private val barH = 20f * density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ConversationColors.TEXT; textSize = 10f * density }
    private val maxOffset = turn.stages.mapNotNull { it.endOffsetMs }.maxOrNull() ?: 1L

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (barH + 8f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        turn.stages.forEach { s ->
            val sx = width * s.startOffsetMs / maxOffset.toFloat()
            val ex = (if (s.endOffsetMs != null) width * s.endOffsetMs / maxOffset.toFloat()
                       else width.toFloat()).coerceAtLeast(sx + 4f)
            val r = RectF(sx, 2f * density, ex, 2f * density + barH)
            bar.color = ConversationColors.stageColor(s.status)
            canvas.drawRoundRect(r, 3f * density, 3f * density, bar)
            canvas.drawText(s.name.take(5), sx + 2f * density, 2f * density + barH - 4f * density, label)
        }
    }
}
