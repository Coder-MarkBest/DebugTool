package com.debugtools.stability

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.stability.view.StabilityRootView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StabilityModule : DebugModule {

    override val moduleId: String = "stability"
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
}
