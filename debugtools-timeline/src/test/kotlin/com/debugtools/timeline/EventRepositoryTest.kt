package com.debugtools.timeline

import com.debugtools.core.ipc.model.DebugEvent
import org.junit.Assert.*
import org.junit.Test

class EventRepositoryTest {

    @Test fun `add stores events in insertion order`() {
        val repo = EventRepository(maxSize = 100)
        val e1 = DebugEvent(1L, "A")
        val e2 = DebugEvent(2L, "B")
        repo.add(e1); repo.add(e2)
        assertEquals(listOf(e1, e2), repo.snapshot())
    }

    @Test fun `evicts oldest when at capacity`() {
        val repo = EventRepository(maxSize = 3)
        (1..5).forEach { repo.add(DebugEvent(it.toLong(), "tag$it")) }
        val snap = repo.snapshot()
        assertEquals(3, snap.size)
        assertEquals("tag3", snap[0].tag)
        assertEquals("tag5", snap[2].tag)
    }

    @Test fun `clear removes all events`() {
        val repo = EventRepository(maxSize = 10)
        repo.add(DebugEvent(1L, "A"))
        repo.clear()
        assertTrue(repo.snapshot().isEmpty())
    }

    @Test fun `snapshot returns a copy (not live reference)`() {
        val repo = EventRepository(maxSize = 10)
        repo.add(DebugEvent(1L, "A"))
        val snap = repo.snapshot()
        repo.add(DebugEvent(2L, "B"))
        assertEquals(1, snap.size)  // snapshot is not affected by subsequent adds
    }
}
