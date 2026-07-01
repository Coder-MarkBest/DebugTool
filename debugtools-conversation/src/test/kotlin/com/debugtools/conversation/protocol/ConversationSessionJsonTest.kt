package com.debugtools.conversation.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationSessionJsonTest {

    private fun sample() = ConversationSession(
        sessionId = "s1",
        startedAtWallMs = 1000L,
        metadata = mapOf("scene" to "导航中"),
        turns = listOf(
            ConversationTurn(
                turnId = "t1", turnIndex = 1, sessionId = "s1",
                startUptimeMs = 1100L, endUptimeMs = 1500L,
                userInput = "导航到最近的加油站",
                stages = listOf(
                    TurnStage("唤醒", 0, 200, StageStatus.SUCCESS, null, null, null, "main"),
                    TurnStage("ASR", 200, 600, StageStatus.SUCCESS, "[audio]", "导航到最近的加油站", null, "asr-1"),
                    TurnStage("NLU", 600, 650, StageStatus.SUCCESS, "导航到最近的加油站", """{"intent":"导航","slots":{"dest":"加油站"}}""", null, "nlu-1"),
                    TurnStage("TTS", 650, 900, StageStatus.SUCCESS, null, "已为您找到最近的加油站", null, "tts-1"),
                    TurnStage("执行", 900, null, StageStatus.FAILED, null, null, "NavigationService timeout", "exec-1")
                ),
                outcome = TurnOutcome.FAILED,
                tags = listOf("导航")
            )
        ),
        endedAtWallMs = 2000L
    )

    @Test fun `session round-trips through json`() {
        val json = sample().toJson()
        val back = ConversationSession.fromJson(JSONObject(json.toString()))
        assertEquals(sample(), back)
    }

    @Test fun `nulls survive round-trip`() {
        val back = ConversationSession.fromJson(JSONObject(sample().toJson().toString()))
        val failed = back.turns.single().stages.first { it.name == "执行" }
        assertNull(failed.endOffsetMs)
        assertNull(failed.input)
        assertNull(failed.output)
        assertEquals(StageStatus.FAILED, failed.status)
    }
}
