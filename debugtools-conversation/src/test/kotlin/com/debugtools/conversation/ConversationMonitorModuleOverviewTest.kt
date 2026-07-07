package com.debugtools.conversation

import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnOutcome
import com.debugtools.conversation.protocol.TurnStage
import com.debugtools.core.overview.OverviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMonitorModuleOverviewTest {
    @Test fun `overview is unknown when no conversation data exists`() {
        val item = ConversationMonitorModule.overviewItem(null)

        assertEquals("conversation", item.moduleId)
        assertEquals("对话链路", item.title)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
        assertEquals("暂无对话数据", item.primaryText)
    }

    @Test fun `overview reports failed conversation as error`() {
        val item = ConversationMonitorModule.overviewItem(
            session(
                turn(
                    outcome = TurnOutcome.FAILED,
                    stages = listOf(
                        stage("ASR", 0, 120, StageStatus.SUCCESS),
                        stage("NLU", 120, 180, StageStatus.FAILED, "intent timeout")
                    )
                )
            )
        )

        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("1失败"))
    }

    @Test fun `overview reports slow conversation as warning`() {
        val item = ConversationMonitorModule.overviewItem(
            session(
                turn(
                    outcome = TurnOutcome.SUCCESS,
                    stages = listOf(stage("ASR", 0, 700, StageStatus.SUCCESS))
                )
            )
        )

        assertEquals(OverviewStatus.WARNING, item.status)
        assertTrue(item.secondaryText.orEmpty().contains("慢阶段 1"))
    }

    private fun session(turn: ConversationTurn) = ConversationSession(
        sessionId = "request-1",
        startedAtWallMs = 1000L,
        metadata = null,
        turns = listOf(turn),
        endedAtWallMs = 2000L
    )

    private fun turn(
        outcome: TurnOutcome,
        stages: List<TurnStage>
    ) = ConversationTurn(
        turnId = "turn-1",
        turnIndex = 1,
        sessionId = "request-1",
        startUptimeMs = 10L,
        endUptimeMs = 900L,
        userInput = "导航到公司",
        stages = stages,
        outcome = outcome,
        tags = null
    )

    private fun stage(
        name: String,
        start: Long,
        end: Long,
        status: StageStatus,
        error: String? = null
    ) = TurnStage(name, start, end, status, null, null, error, "test")
}
