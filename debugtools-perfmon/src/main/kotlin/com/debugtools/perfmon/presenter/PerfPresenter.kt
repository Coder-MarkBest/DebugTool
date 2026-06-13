package com.debugtools.perfmon.presenter

import com.debugtools.perfmon.repository.PerfRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

class PerfPresenter(
    private val repository: PerfRepository,
    private val scope: CoroutineScope,
    private val sampleMs: Long = 200L,
    private val onSelectPid: (Int?) -> Unit
) {
    private var view: PerfView? = null
    private var job: Job? = null
    private val selectedTargetKey = MutableStateFlow<String?>(null)

    fun attachView(view: PerfView) {
        this.view = view
        job = scope.launch {
            val source = combine(repository.state, selectedTargetKey) { state, selKey ->
                Pair(state, selKey)
            }
                .let { if (sampleMs > 0) it.sample(sampleMs) else it }
                .distinctUntilChanged()

            source.collect { (state, selKey) ->
                val rows = buildRows(state, selKey)
                this@PerfPresenter.view?.showList(rows)
                val selSeries = selKey?.let {
                    state.series[it]?.snapshot() ?: emptyList()
                } ?: emptyList()
                this@PerfPresenter.view?.showDetail(state.detail, selSeries)
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        view = null
    }

    fun selectTarget(targetKey: String?) {
        selectedTargetKey.value = targetKey
        val pid = targetKey?.let { key ->
            repository.state.value.series[key]?.snapshot()?.lastOrNull()?.value?.pid
        }
        onSelectPid(pid)
    }

    private fun buildRows(state: PerfRepository.Snapshot, selectedKey: String?): List<ProcessRow> {
        return state.series.values.map { series ->
            val last = series.snapshot().lastOrNull()?.value
                ?: return@map null
            val displayName = when (val t = last.target) {
                is com.debugtools.perfmon.data.ProcessTarget.ByName -> t.processName
                is com.debugtools.perfmon.data.ProcessTarget.ByPid -> "pid ${t.pid}"
            }
            ProcessRow(
                targetKey = last.target.key,
                displayName = displayName,
                pid = last.pid,
                cpuPercent = last.cpuPercent,
                rssBytes = last.rssBytes,
                threadCount = last.threadCount,
                alive = last.alive,
                selected = (selectedKey == last.target.key)
            )
        }.filterNotNull()
    }
}
