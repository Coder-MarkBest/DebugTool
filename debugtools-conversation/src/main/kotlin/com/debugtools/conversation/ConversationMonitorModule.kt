package com.debugtools.conversation

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.overview.OverviewItem
import com.debugtools.core.overview.OverviewMetric
import com.debugtools.core.overview.OverviewProvider
import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.ModuleRecordingSnapshot
import com.debugtools.core.recording.RecordableModule
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.recording.RecordingIssue
import com.debugtools.core.recording.RecordingIssueSeverity
import com.debugtools.core.settings.SettingGroup
import com.debugtools.conversation.analyzer.TurnAnalyzer
import com.debugtools.conversation.recording.ConversationRecordingExporter
import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.TurnIssueSeverity
import com.debugtools.conversation.protocol.TurnIssueType
import com.debugtools.conversation.protocol.TurnOutcome
import com.debugtools.conversation.view.ConversationRootView
import com.debugtools.conversation.view.VoiceTraceRootView

/**
 * Debug module that shows captured conversation sessions (persisted + the current one).
 *
 * The host adapter layer maps its conversation logs to [ConversationTurn] and submits
 * through [ConversationTracer]; this module just reads and visualizes. Register it like
 * any other module:
 * ```kotlin
 * DebugTools.builder(context).register(ConversationMonitorModule()).build()
 * ```
 */
class ConversationMonitorModule : DebugModule, RecordableModule, OverviewProvider {

    override val moduleId: String = "conversation"
    override val tabTitle: String = "对话链路"
    override val recorderId: String = moduleId

    private var appContext: Context? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        appContext = context.applicationContext
    }

    override fun onDetach() { appContext = null }

    override fun createContentView(context: Context): View {
        if (VoiceTrace.currentProfile() != null) {
            return VoiceTraceRootView(
                context = context,
                loadSnapshot = { VoiceTrace.snapshot() },
                loadProfile = { VoiceTrace.currentProfile() }
            )
        }
        return ConversationRootView(context) {
            mergeCurrent(ConversationTracer.currentSession(), ConversationTracer.loadSessions())
        }
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun getBriefItems(): List<BriefItem> {
        val current = ConversationTracer.currentSession() ?: return emptyList()
        val fail = current.turns.count { it.outcome.name == "FAILED" }
        return listOf(BriefItem(text = "对话 ${current.turns.size}轮" + if (fail > 0) " · ${fail}失败" else ""))
    }

    override fun getOverviewItems(): List<OverviewItem> {
        val sessions = mergeCurrent(ConversationTracer.currentSession(), ConversationTracer.loadSessions())
        return listOf(overviewItem(sessions.maxByOrNull { it.startedAtWallMs }))
    }

    override fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot =
        ModuleRecordingSnapshot(moduleId)

    override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
        val profile = VoiceTrace.currentProfile()
        val recorder = VoiceTrace.currentRecorder()
        if (profile == null || recorder == null) {
            val issue = RecordingIssue(
                severity = RecordingIssueSeverity.INFO,
                type = "VOICE_TRACE_NOT_INITIALIZED",
                detail = "VoiceTrace.init was not called before recording stopped",
                moduleId = moduleId
            )
            return ModuleRecordingResult(moduleId = moduleId, issues = listOf(issue))
        }
        return ConversationRecordingExporter(profile, recorder).export(context)
    }

    private fun mergeCurrent(current: ConversationSession?, persisted: List<ConversationSession>): List<ConversationSession> {
        if (current == null) return persisted
        val rest = persisted.filter { it.sessionId != current.sessionId }
        return listOf(current) + rest
    }

    companion object {
        fun overviewItem(session: ConversationSession?): OverviewItem {
            if (session == null) {
                return OverviewItem(
                    moduleId = "conversation",
                    title = "对话链路",
                    status = OverviewStatus.UNKNOWN,
                    primaryText = "暂无对话数据"
                )
            }
            val issues = session.turns.flatMap { TurnAnalyzer.analyze(it) }
            val failedTurns = session.turns.count { it.outcome == TurnOutcome.FAILED }
            val errorIssues = issues.count { it.severity == TurnIssueSeverity.ERROR }
            val slowStages = issues.count { it.type == TurnIssueType.SLOW_STAGE }
            val warnIssues = issues.count { it.severity == TurnIssueSeverity.WARN }
            val status = when {
                failedTurns > 0 || errorIssues > 0 -> OverviewStatus.ERROR
                slowStages > 0 || warnIssues > 0 -> OverviewStatus.WARNING
                else -> OverviewStatus.OK
            }
            return OverviewItem(
                moduleId = "conversation",
                title = "对话链路",
                status = status,
                primaryText = "最近 ${session.sessionId} · ${session.turns.size}轮" +
                    if (failedTurns > 0) " · ${failedTurns}失败" else "",
                secondaryText = "慢阶段 $slowStages · 问题 ${issues.size}",
                metrics = listOf(
                    OverviewMetric("轮次", session.turns.size.toString(), OverviewStatus.UNKNOWN),
                    OverviewMetric("失败", failedTurns.toString(), if (failedTurns > 0) OverviewStatus.ERROR else OverviewStatus.OK),
                    OverviewMetric("慢阶段", slowStages.toString(), if (slowStages > 0) OverviewStatus.WARNING else OverviewStatus.OK)
                )
            )
        }
    }
}
