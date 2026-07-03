package com.debugtools.conversation

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.ModuleRecordingSnapshot
import com.debugtools.core.recording.RecordableModule
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.recording.RecordingIssue
import com.debugtools.core.recording.RecordingIssueSeverity
import com.debugtools.core.settings.SettingGroup
import com.debugtools.conversation.recording.ConversationRecordingExporter
import com.debugtools.conversation.protocol.ConversationSession
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
class ConversationMonitorModule : DebugModule, RecordableModule {

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
}
