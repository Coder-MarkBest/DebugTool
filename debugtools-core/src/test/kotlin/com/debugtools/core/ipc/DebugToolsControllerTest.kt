package com.debugtools.core.ipc

import com.debugtools.core.ipc.model.CrashInfo
import com.debugtools.core.ipc.model.DebugEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DebugToolsControllerTest {
    private lateinit var controller: DebugToolsController

    @Before fun setUp() {
        controller = DebugToolsController()
    }

    @Test fun `sendEvent dispatches to registered listener`() {
        var received: DebugEvent? = null
        controller.setEventListener { received = it }
        val event = DebugEvent(timestamp = 1L, tag = "test")
        controller.sendEvent(event)
        assertEquals(event, received)
    }

    @Test fun `reportCrash dispatches to registered listener`() {
        var received: CrashInfo? = null
        controller.setCrashListener { received = it }
        val crash = CrashInfo(1L, "main", "NullPointerException", null, "at com.example.Foo")
        controller.reportCrash(crash)
        assertEquals(crash, received)
    }

    @Test fun `sendEvent does not throw when no listener registered`() {
        controller.sendEvent(DebugEvent(1L, "tag"))  // should not throw
    }

    @Test fun `reportCrash does not throw when no listener registered`() {
        controller.reportCrash(CrashInfo(1L, "main", "Exception", null, "stack"))
    }

    @Test fun `listener can be replaced`() {
        var first = 0; var second = 0
        controller.setEventListener { first++ }
        controller.setEventListener { second++ }
        controller.sendEvent(DebugEvent(1L, "tag"))
        assertEquals(0, first)
        assertEquals(1, second)
    }
}
