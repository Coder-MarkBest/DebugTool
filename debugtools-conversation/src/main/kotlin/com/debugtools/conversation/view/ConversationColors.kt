package com.debugtools.conversation.view

import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnOutcome

object ConversationColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val ACCENT = 0xFF2DD4BF.toInt()
    val SUCCESS = 0xFF48BB78.toInt()
    val FAILED = 0xFFF43F5E.toInt()
    val WARN = 0xFFF6AD55.toInt()
    val NEUTRAL = 0xFF64748B.toInt()
    val EDGE = 0xFF4A5568.toInt()

    fun stageColor(s: StageStatus): Int = when (s) {
        StageStatus.SUCCESS -> SUCCESS
        StageStatus.FAILED -> FAILED
        StageStatus.RUNNING -> NEUTRAL
        StageStatus.SKIPPED -> TEXT_DIM
    }

    fun outcomeColor(o: TurnOutcome): Int = when (o) {
        TurnOutcome.SUCCESS -> SUCCESS
        TurnOutcome.FAILED -> FAILED
        TurnOutcome.TIMEOUT -> WARN
        TurnOutcome.ABORTED -> TEXT_DIM
    }
}
