package com.debugtools.conversation.analyzer

import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnIssue
import com.debugtools.conversation.protocol.TurnIssueSeverity
import com.debugtools.conversation.protocol.TurnIssueType
import com.debugtools.conversation.protocol.TurnOutcome

/** Pure diagnostics over a completed conversation turn. */
object TurnAnalyzer {

    const val DEFAULT_SLOW_STAGE_MS = 500L

    fun analyze(turn: ConversationTurn, slowThresholdMs: Long = DEFAULT_SLOW_STAGE_MS): List<TurnIssue> {
        val issues = mutableListOf<TurnIssue>()

        for (s in turn.stages) {
            val dur = if (s.endOffsetMs != null) s.endOffsetMs - s.startOffsetMs else null

            if (s.status == StageStatus.FAILED) {
                issues += TurnIssue(TurnIssueType.STAGE_FAILED, s.name,
                    s.error ?: "阶段执行失败", TurnIssueSeverity.ERROR)
            }
            if (dur != null && dur > slowThresholdMs) {
                issues += TurnIssue(TurnIssueType.SLOW_STAGE, s.name,
                    "耗时 ${dur}ms", TurnIssueSeverity.WARN)
            }
        }

        if (turn.outcome == TurnOutcome.TIMEOUT) {
            issues += TurnIssue(TurnIssueType.TURN_TIMEOUT, null,
                "轮次超时，总耗时 ${turn.endUptimeMs?.minus(turn.startUptimeMs) ?: "?"}ms", TurnIssueSeverity.WARN)
        }
        if (turn.outcome == TurnOutcome.ABORTED) {
            issues += TurnIssue(TurnIssueType.TURN_ABORTED, null, "轮次被中断", TurnIssueSeverity.WARN)
        }

        // pipeline gaps: stage[N].startOffsetMs > stage[N-1].endOffsetMs
        turn.stages.zipWithNext { prev, curr ->
            val prevEnd = prev.endOffsetMs ?: return@zipWithNext
            if (curr.startOffsetMs > prevEnd) {
                val gap = curr.startOffsetMs - prevEnd
                issues += TurnIssue(TurnIssueType.PIPELINE_GAP, curr.name,
                    "前一阶段结束到本阶段开始间隔 ${gap}ms，流水线存在空闲", TurnIssueSeverity.INFO)
            }
        }

        return issues
    }
}
