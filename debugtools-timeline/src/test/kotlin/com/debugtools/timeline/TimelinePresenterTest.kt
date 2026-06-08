package com.debugtools.timeline

import com.debugtools.core.ipc.model.DebugEvent
import org.junit.Assert.*
import org.junit.Test

class TimelinePresenterTest {

    @Test fun `onEvent adds to repository and notifies view`() {
        val repo = EventRepository(maxSize = 10)
        var received: List<DebugEvent>? = null
        val view = object : TimelineView {
            override fun showEvents(events: List<DebugEvent>) { received = events }
        }
        val presenter = TimelinePresenter(repo)
        presenter.attachView(view)
        presenter.onEvent(DebugEvent(1L, "test"))
        assertEquals(1, received?.size)
        assertEquals("test", received?.firstOrNull()?.tag)
    }

    @Test fun `attachView triggers immediate refresh`() {
        val repo = EventRepository(maxSize = 10)
        repo.add(DebugEvent(1L, "existing"))
        var received: List<DebugEvent>? = null
        val view = object : TimelineView {
            override fun showEvents(events: List<DebugEvent>) { received = events }
        }
        val presenter = TimelinePresenter(repo)
        presenter.attachView(view)
        assertEquals(1, received?.size)
    }

    @Test fun `detach stops view updates`() {
        val repo = EventRepository(maxSize = 10)
        var callCount = 0
        val view = object : TimelineView {
            override fun showEvents(events: List<DebugEvent>) { callCount++ }
        }
        val presenter = TimelinePresenter(repo)
        presenter.attachView(view)
        callCount = 0  // reset after initial refresh
        presenter.detach()
        presenter.onEvent(DebugEvent(2L, "after-detach"))
        assertEquals(0, callCount)
    }
}
