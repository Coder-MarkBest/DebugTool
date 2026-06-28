package com.debugtools.startup.store

import com.debugtools.startup.protocol.StartupSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun session(id: String, wall: Long) = StartupSession(
        sessionId = id, startedAtWallMs = wall, launchUptimeMs = 0L, appVersion = null,
        steps = emptyList(), completedUptimeMs = 1L, completedExplicitly = true
    )

    @Test fun `save then load returns the session`() {
        val store = StartupStore(tmp.root)
        store.save(session("a", 100L))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("a", loaded.first().sessionId)
    }

    @Test fun `load returns most-recent first`() {
        val store = StartupStore(tmp.root)
        store.save(session("old", 100L))
        store.save(session("new", 200L))
        assertEquals(listOf("new", "old"), store.load().map { it.sessionId })
    }

    @Test fun `keeps only the most recent maxSessions`() {
        val store = StartupStore(tmp.root, maxSessions = 3)
        (1..5).forEach { store.save(session("s$it", it * 100L)) }
        assertEquals(listOf("s5", "s4", "s3"), store.load().map { it.sessionId })
    }

    @Test fun `corrupt file is skipped, others still load`() {
        val store = StartupStore(tmp.root)
        store.save(session("good", 100L))
        tmp.root.resolve("050_bad.json").writeText("{ not valid json")
        assertEquals(listOf("good"), store.load().map { it.sessionId })
    }
}
