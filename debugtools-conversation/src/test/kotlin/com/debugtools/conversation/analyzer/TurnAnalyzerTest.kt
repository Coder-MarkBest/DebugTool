package com.debugtools.conversation.analyzer

import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnIssueType
import com.debugtools.conversation.protocol.TurnOutcome
import com.debugtools.conversation.protocol.TurnStage
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnAnalyzerTest {

    private fun stage(name: String, start: Long, end: Long?, status: StageStatus, error: String? = null) =
        TurnStage(name, start, end, status, null, null, error, "t")

    private fun turn(stages: List<TurnStage>, outcome: TurnOutcome = TurnOutcome.SUCCESS) =
        ConversationTurn("t1", 1, "s1", 0L, stages.mapNotNull { it.endOffsetMs }.maxOrNull(),
            null, stages, outcome, null)

    private fun types(t: ConversationTurn) = TurnAnalyzer.analyze(t).map { it.type }

    @Test fun `failed stage is STAGE_FAILED`() {
        val t = turn(listOf(stage("ASR", 0, 100, StageStatus.FAILED, "Engine crash")))
        assertTrue(types(t).contains(TurnIssueType.STAGE_FAILED))
    }

    @Test fun `stage over slow threshold is SLOW_STAGE`() {
        val t = turn(listOf(stage("TTS", 0, 800, StageStatus.SUCCESS))) // 800ms > 500
        assertTrue(types(t).contains(TurnIssueType.SLOW_STAGE))
    }

    @Test fun `fast stage is not SLOW_STAGE`() {
        val t = turn(listOf(stage("NLU", 0, 50, StageStatus.SUCCESS))) // 50ms < 500
        assertTrue(!types(t).contains(TurnIssueType.SLOW_STAGE))
    }

    @Test fun `TIMEOUT outcome is TURN_TIMEOUT`() {
        val t = turn(listOf(stage("ASR", 0, 5000, StageStatus.SUCCESS)), TurnOutcome.TIMEOUT)
        assertTrue(types(t).contains(TurnIssueType.TURN_TIMEOUT))
    }

    @Test fun `ABORTED outcome is TURN_ABORTED`() {
        val t = turn(listOf(stage("ASR", 0, 100, StageStatus.SUCCESS)), TurnOutcome.ABORTED)
        assertTrue(types(t).contains(TurnIssueType.TURN_ABORTED))
    }

    @Test fun `gap between consecutive stages is PIPELINE_GAP`() {
        val t = turn(listOf(
            stage("ASR", 0, 100, StageStatus.SUCCESS),
            stage("NLU", 200, 250, StageStatus.SUCCESS) // 100ms gap after ASR
        ))
        assertTrue(types(t).contains(TurnIssueType.PIPELINE_GAP))
    }

    @Test fun `contiguous stages have no PIPELINE_GAP`() {
        val t = turn(listOf(
            stage("ASR", 0, 100, StageStatus.SUCCESS),
            stage("NLU", 100, 150, StageStatus.SUCCESS) // exactly contiguous
        ))
        assertTrue(!types(t).contains(TurnIssueType.PIPELINE_GAP))
    }
}
