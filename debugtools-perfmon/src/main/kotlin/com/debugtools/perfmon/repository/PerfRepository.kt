package com.debugtools.perfmon.repository

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimeSeries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory storage for all per-process time series plus the (single) selected
 * process detail. Exposes a [Snapshot] StateFlow consumed by the UI.
 *
 * Per-target series share the same windowSec / intervalSec from [Config], so each
 * series holds at most windowSec/intervalSec + 1 samples (e.g. 180 for 30min/10s).
 */
class PerfRepository(private val config: Config) {

    /** Immutable snapshot for UI. The TimeSeries instances inside are also internally
     *  immutable views; UI only reads via [TimeSeries.snapshot]. */
    data class Snapshot(
        val series: Map<String, TimeSeries<ProcessSample>>,
        val detail: ProcessDetail?
    )

    private val seriesByTargetKey = LinkedHashMap<String, TimeSeries<ProcessSample>>()
    private val _state = MutableStateFlow(Snapshot(series = emptyMap(), detail = null))
    val state: StateFlow<Snapshot> = _state
    private var detail: ProcessDetail? = null

    @Synchronized
    fun addSample(sample: ProcessSample) {
        val key = sample.target.key
        val series = seriesByTargetKey.getOrPut(key) {
            TimeSeries(windowSec = config.windowSec, intervalSec = config.updateIntervalSec)
        }
        series.add(sample.timestamp, sample)
        publish()
    }

    @Synchronized
    fun setDetail(d: ProcessDetail) {
        detail = d
        publish()
    }

    @Synchronized
    fun clearDetail() {
        detail = null
        publish()
    }

    private fun publish() {
        _state.value = Snapshot(
            series = seriesByTargetKey.toMap(),
            detail = detail
        )
    }
}
