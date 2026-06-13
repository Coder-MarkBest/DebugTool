package com.debugtools.perfmon.presenter

import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimedValue

interface PerfView {
    fun showList(rows: List<ProcessRow>)
    fun showDetail(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>)
}

/** Per-row state for the left-side list. */
data class ProcessRow(
    val targetKey: String,
    val displayName: String,    // process name or pid
    val pid: Int?,
    val cpuPercent: Float,
    val rssBytes: Long,
    val threadCount: Int,
    val alive: Boolean,
    val selected: Boolean
)
