package com.debugtools.core.recording

import android.os.SystemClock
import com.debugtools.core.module.DebugModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class DebugRecordingManager(
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val uptimeClock: () -> Long = { SystemClock.uptimeMillis() }
) {
    private val lock = Any()
    private var activeModules: List<RecordableModule> = emptyList()
    private var activeContext: RecordingContext? = null
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    fun isActive(): Boolean = activeContext != null

    fun start(modules: List<DebugModule>, recordingsRoot: File): RecordingContext = synchronized(lock) {
        check(activeContext == null) { "Recording already active" }
        val id = recordingId()
        val dir = File(recordingsRoot, id).apply { mkdirs() }
        val context = RecordingContext(id, wallClock(), uptimeClock(), dir)
        activeModules = modules.filterIsInstance<RecordableModule>()
        activeModules.forEach { runCatching { it.onRecordingStart(context) } }
        activeContext = context
        _state.value = RecordingState.Active(context)
        context
    }

    fun stop(): RecordingSessionReport {
        val context: RecordingContext
        val modules: List<RecordableModule>
        synchronized(lock) {
            context = activeContext ?: error("No active recording")
            modules = activeModules
            activeContext = null
            activeModules = emptyList()
            _state.value = RecordingState.Idle
        }

        val results = mutableListOf<ModuleRecordingResult>()
        val issues = mutableListOf<RecordingIssue>()
        for (module in modules) {
            val result = runCatching { module.onRecordingStop(context) }
            if (result.isSuccess) {
                results += result.getOrThrow()
            } else {
                val issue = RecordingIssue(
                    severity = RecordingIssueSeverity.WARNING,
                    type = "MODULE_EXPORT_FAILED",
                    detail = result.exceptionOrNull()?.message ?: "export failed",
                    moduleId = module.recorderId
                )
                issues += issue
                results += ModuleRecordingResult(module.recorderId, issues = listOf(issue))
            }
        }
        return RecordingSessionReport(context, wallClock(), context.rootDir, results, issues)
    }

    private fun recordingId(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val suffix = (1..4).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        return "${ts}_$suffix"
    }
}
