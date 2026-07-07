package com.debugtools.conversation

import com.debugtools.conversation.trace.LinkTraceEvent
import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.TraceOutcome
import com.debugtools.conversation.trace.linkTraceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LinkTraceTest {
    @Test
    fun `link trace facade records business neutral events`() {
        val profile = linkTraceProfile {
            requestBoundary { exitEvents = listOf("OrderDone") }
            stage("pay") {
                begin = "PayBegin"
                end = "PayEnd"
                label = "Payment"
            }
        }

        LinkTrace.init(profile)
        LinkTrace.mark(LinkTraceEvent(traceId = "order-1", name = "PayBegin", type = TraceEventType.BEGIN, timestampUptimeMs = 100))
        LinkTrace.mark(LinkTraceEvent(traceId = "order-1", name = "PayEnd", type = TraceEventType.END, timestampUptimeMs = 200))
        LinkTrace.mark(LinkTraceEvent(traceId = "order-1", name = "OrderDone", type = TraceEventType.INSTANT, timestampUptimeMs = 210, attributes = mapOf("outcome" to TraceOutcome.SUCCESS.name)))

        val snapshot = LinkTrace.snapshotForTest()
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.eventsByRequest.getValue("order-1").size)
    }

    @Test
    fun `link trace mark accepts generic event type`() {
        val profile = linkTraceProfile()
        LinkTrace.init(profile)

        LinkTrace.mark(LinkTraceEvent(traceId = "task-1", name = "TaskStarted", type = TraceEventType.INSTANT, timestampUptimeMs = 100))

        assertEquals("TaskStarted", LinkTrace.snapshotForTest()!!.eventsByRequest.getValue("task-1").single().name)
    }
}
