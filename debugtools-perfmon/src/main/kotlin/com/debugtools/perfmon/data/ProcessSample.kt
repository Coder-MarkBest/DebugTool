package com.debugtools.perfmon.data

/** Tier 1 row: cheap per-process snapshot taken every interval for all targets. */
data class ProcessSample(
    val target: ProcessTarget,
    val pid: Int?,              // null when process is gone
    val timestamp: Long,
    val cpuPercent: Float,      // 0..100*coreCount  (top-style)
    val rssBytes: Long,
    val threadCount: Int,
    val alive: Boolean
)
