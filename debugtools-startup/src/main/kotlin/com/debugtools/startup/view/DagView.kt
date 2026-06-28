package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.debugtools.startup.protocol.StartupSession

/** Dependency DAG: nodes laid out in dependency layers, edges = dependsOn, node color = status. */
@SuppressLint("ViewConstructor")
class DagView(context: Context, private val session: StartupSession) : View(context) {

    private val density = resources.displayMetrics.density
    private val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = StartupColors.EDGE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StartupColors.TEXT; textSize = 10f * density }

    private val byName = session.steps.associateBy { it.name }
    private val layer = HashMap<String, Int>()
    private val centers = HashMap<String, Pair<Float, Float>>()
    private val maxLayer: Int

    init {
        fun depth(name: String, seen: MutableSet<String>): Int {
            if (!seen.add(name)) return 0
            val deps = byName[name]?.dependsOn?.filter { byName.containsKey(it) } ?: emptyList()
            return if (deps.isEmpty()) 0 else 1 + (deps.maxOf { depth(it, seen) })
        }
        session.steps.forEach { layer[it.name] = depth(it.name, hashSetOf()) }
        maxLayer = (layer.values.maxOrNull() ?: 0)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), ((maxLayer + 1) * 50f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val byLayer = session.steps.groupBy { layer[it.name] ?: 0 }
        centers.clear()
        byLayer.forEach { (ly, steps) ->
            val rowY = ly * 50f * density + 24f * density
            steps.forEachIndexed { idx, s ->
                val cx = width * (idx + 1f) / (steps.size + 1f)
                centers[s.name] = cx to rowY
            }
        }
        session.steps.forEach { s ->
            val to = centers[s.name] ?: return@forEach
            s.dependsOn.forEach { d -> centers[d]?.let { from -> canvas.drawLine(from.first, from.second, to.first, to.second, edge) } }
        }
        session.steps.forEach { s ->
            val c = centers[s.name] ?: return@forEach
            node.color = StartupColors.statusColor(s.status)
            val r = 8f * density
            canvas.drawRoundRect(RectF(c.first - 30f * density, c.second - r, c.first + 30f * density, c.second + r), r, r, node)
            canvas.drawText(s.name.take(7), c.first - 28f * density, c.second + 4f * density, text)
        }
    }
}
