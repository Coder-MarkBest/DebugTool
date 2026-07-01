package com.debugtools.conversation.store

import com.debugtools.conversation.protocol.ConversationSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun session(id: String, wall: Long) = ConversationSession(
        sessionId = id, startedAtWallMs = wall, metadata = null,
        turns = emptyList(), endedAtWallMs = wall + 1000L
    )

    @Test fun `save then load returns the session`() {
        val store = ConversationStore(tmp.root)
        store.save(session("a", 100L))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("a", loaded.first().sessionId)
    }

    @Test fun `load returns most-recent first`() {
        val store = ConversationStore(tmp.root)
        store.save(session("old", 100L))
        store.save(session("new", 200L))
        assertEquals(listOf("new", "old"), store.load().map { it.sessionId })
    }

    @Test fun `keeps only the most recent maxSessions`() {
        val store = ConversationStore(tmp.root, maxSessions = 3)
        (1..5).forEach { store.save(session("s$it", it * 100L)) }
        assertEquals(listOf("s5", "s4", "s3"), store.load().map { it.sessionId })
    }

    @Test fun `corrupt file is skipped, others still load`() {
        val store = ConversationStore(tmp.root)
        store.save(session("good", 100L))
        tmp.root.resolve("050_bad.json").writeText("{ not valid json")
        assertEquals(listOf("good"), store.load().map { it.sessionId })
    }
}
