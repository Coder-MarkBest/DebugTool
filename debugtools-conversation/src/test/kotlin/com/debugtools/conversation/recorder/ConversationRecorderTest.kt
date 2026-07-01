package com.debugtools.conversation.recorder

import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnOutcome
import com.debugtools.conversation.protocol.TurnStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRecorderTest {

    private class Clock(var now: Long = 0L) { fun read() = now }

    private fun recorder(clock: Clock) = ConversationRecorder(
        sessionId = "s1", startedAtWallMs = 1000L, clock = clock::read
    )

    private fun turn(id: String, idx: Int, start: Long, end: Long?, outcome: TurnOutcome, stages: List<TurnStage> = emptyList()) =
        ConversationTurn(turnId = id, turnIndex = idx, sessionId = "s1",
            startUptimeMs = start, endUptimeMs = end, userInput = null,
            stages = stages, outcome = outcome, tags = null)

    @Test fun `submitTurn appends to session and keeps index order`() {
        val c = Clock(); c.now = 100; val r = recorder(c)
        r.submitTurn(turn("t1", 1, 100, 200, TurnOutcome.SUCCESS))
        r.submitTurn(turn("t2", 2, 250, 400, TurnOutcome.SUCCESS))
        val s = r.snapshot()
        assertEquals(2, s.turns.size)
        assertEquals(listOf("t1", "t2"), s.turns.map { it.turnId })
    }

    @Test fun `endSession sets endedAtWallMs`() {
        val c = Clock(); c.now = 500; val r = recorder(c)
        r.submitTurn(turn("t1", 1, 100, 200, TurnOutcome.SUCCESS))
        c.now = 600; r.endSession()
        assertEquals(600L, r.snapshot().endedAtWallMs)
    }

    @Test fun `endSession is idempotent`() {
        val c = Clock(); val r = recorder(c)
        c.now = 10; r.endSession()
        c.now = 20; r.endSession()
        assertEquals(10L, r.snapshot().endedAtWallMs)
    }

    @Test fun `isEnded returns false until endSession`() {
        val r = recorder(Clock())
        assertEquals(false, r.isEnded())
        r.endSession()
        assertEquals(true, r.isEnded())
    }

    @Test fun `snapshot returns session with zero turns when empty`() {
        val s = recorder(Clock()).snapshot()
        assertEquals("s1", s.sessionId)
        assertEquals(0, s.turns.size)
    }

    @Test fun `startSession updates metadata`() {
        val r = recorder(Clock())
        r.startSession(mapOf("scene" to "导航"))
        assertEquals(mapOf("scene" to "导航"), r.snapshot().metadata)
    }
}
