package com.debugtools.startup

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.ModuleRecordingSnapshot
import com.debugtools.core.recording.RecordableModule
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.settings.SettingGroup
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StepStatus
import com.debugtools.startup.store.StartupStore
import com.debugtools.startup.view.StartupRootView
import java.io.File
import org.json.JSONArray

/**
 * Debug module that shows captured app-startup sessions (persisted + the current one).
 *
 * The host reports via [AppStartupMonitor] from Application.onCreate; this module just
 * reads and visualizes. Register it like any other module:
 * ```kotlin
 * DebugTools.builder(context).register(StartupMonitorModule()).build()
 * ```
 */
class StartupMonitorModule : DebugModule, RecordableModule {

    override val moduleId: String = "startup"
    override val recorderId: String = moduleId
    override val tabTitle: String = "启动链路"

    private var appContext: Context? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        appContext = context.applicationContext
    }

    override fun onDetach() { appContext = null }

    override fun createContentView(context: Context): View {
        val store = StartupStore(File(context.applicationContext.filesDir, "startup"))
        // Pass a loader (not a static list): the overlay caches this view, so it must
        // re-read the store + current session each time the tab is (re)opened.
        return StartupRootView(context) {
            mergeCurrent(AppStartupMonitor.currentSession(), store.load())
        }
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot {
        val current = AppStartupMonitor.currentSession()
        return ModuleRecordingSnapshot(
            moduleId = moduleId,
            summary = mapOf(
                "currentSession" to (current?.sessionId ?: "none"),
                "currentSteps" to (current?.steps?.size ?: 0).toString()
            )
        )
    }

    override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
        val app = appContext
        val persisted = app?.let { StartupStore(File(it.filesDir, "startup")).load() }.orEmpty()
        val sessions = mergeCurrent(AppStartupMonitor.currentSession(), persisted)
        val dir = File(context.rootDir, moduleId).apply { mkdirs() }
        val file = File(dir, "sessions.json")
        file.writeText(JSONArray().apply { sessions.forEach { put(it.toJson()) } }.toString(2))
        val failures = sessions.sumOf { session -> session.steps.count { it.status == StepStatus.FAILED } }
        return ModuleRecordingResult(
            moduleId = moduleId,
            files = listOf(file),
            summary = mapOf(
                "sessions" to sessions.size.toString(),
                "failedSteps" to failures.toString()
            )
        )
    }

    override fun getBriefItems(): List<BriefItem> {
        val current = AppStartupMonitor.currentSession() ?: return emptyList()
        val fail = current.steps.count { it.status == StepStatus.FAILED }
        return listOf(BriefItem(text = "启动 ${current.steps.size}步" + if (fail > 0) " · ${fail}失败" else ""))
    }

    private fun mergeCurrent(current: StartupSession?, persisted: List<StartupSession>): List<StartupSession> {
        if (current == null) return persisted
        val rest = persisted.filter { it.sessionId != current.sessionId }
        return listOf(current) + rest
    }
}
