package com.debugtools.stability

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
import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.view.StabilityRootView
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class StabilityModule : DebugModule, RecordableModule {

    override val moduleId: String = "stability"
    override val recorderId: String = moduleId
    override val tabTitle: String = "稳定性"

    private var rootView: StabilityRootView? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null
    private var searchJob: Job? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startTimer()
    }

    override fun onDetach() {
        timerJob?.cancel()
        searchJob?.cancel()
        scope.cancel()
        rootView = null
    }

    override fun createContentView(context: Context): View {
        rootView = StabilityRootView(context) { refreshAsync() }
        refreshAsync()
        return rootView!!
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot {
        val status = StabilityMonitor.processAliveStatus()
        return ModuleRecordingSnapshot(
            moduleId = moduleId,
            summary = mapOf(
                "processes" to status.size.toString(),
                "deadProcesses" to status.count { !it.value }.toString()
            )
        )
    }

    override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
        val status = StabilityMonitor.processAliveStatus()
        val entries = StabilityMonitor.searchNow()
        val dir = File(context.rootDir, moduleId).apply { mkdirs() }
        val file = File(dir, "stability.json")
        file.writeText(JSONObject().apply {
            put("processAliveStatus", JSONObject().apply {
                status.forEach { (name, alive) -> put(name, alive) }
            })
            put("crashes", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        }.toString(2))
        return ModuleRecordingResult(
            moduleId = moduleId,
            files = listOf(file),
            summary = mapOf(
                "processes" to status.size.toString(),
                "deadProcesses" to status.count { !it.value }.toString(),
                "crashes" to entries.size.toString()
            )
        )
    }

    override fun getBriefItems(): List<BriefItem> {
        val status = StabilityMonitor.processAliveStatus()
        val dead = status.count { !it.value }
        if (dead == 0) return listOf(BriefItem(text = "全部进程正常"))
        return listOf(BriefItem(text = "$dead 个进程异常"))
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                refreshAsync()
            }
        }
    }

    private fun refreshAsync() {
        val view = rootView ?: return
        searchJob?.cancel()
        searchJob = scope.launch {
            withContext(Dispatchers.Main) { view.renderLoading() }
            val result = runCatching {
                StabilityMonitor.processAliveStatus() to StabilityMonitor.searchNow()
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { (status, entries) -> view.renderData(status, entries) },
                    onFailure = { error -> view.renderError(error.message ?: "unknown error") }
                )
            }
        }
    }

    private fun CrashEntry.toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("processName", processName)
        put("timestamp", timestamp)
        put("sourcePath", sourcePath)
        put("pid", pid)
        put("stackTrace", stackTrace)
    }
}
