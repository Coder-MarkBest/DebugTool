package com.debugtools.conversation.view

import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.debugtools.conversation.trace.TraceCategory
import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.TraceGraphEdge
import com.debugtools.conversation.trace.TraceGraphNode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TraceGraphViewTest {
    @Test
    fun `graph lays out horizontally so details remain near the chart`() {
        val view = TraceGraphView(ApplicationProvider.getApplicationContext())
        val nodes = (0 until 8).map { index ->
            TraceGraphNode(
                eventName = "event$index",
                label = "节点$index",
                timestampUptimeMs = index * 100L,
                type = TraceEventType.INSTANT,
                category = TraceCategory.CUSTOM,
                ruleId = null,
                attributes = emptyMap()
            )
        }
        val edges = nodes.zipWithNext { from, to ->
            TraceGraphEdge(from.eventName, to.eventName, to.timestampUptimeMs - from.timestampUptimeMs)
        }

        view.setGraph(nodes, edges) {}
        view.measure(
            View.MeasureSpec.makeMeasureSpec(420, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST)
        )

        assertTrue("horizontal graph should be wider than the visible panel", view.measuredWidth > 420)
        assertTrue("horizontal graph should keep detail visible without a tall timeline", view.measuredHeight <= 150)
    }
}
