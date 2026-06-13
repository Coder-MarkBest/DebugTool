package com.debugtools.perfmon.data

/** Tier 2 row: expensive detail snapshot, only for the user-selected process. */
data class ProcessDetail(
    val pid: Int,
    val timestamp: Long,
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val otherPssKb: Int,
    val threads: List<ThreadInfo>,                       // Top N by CPU%
    val threadStateDistribution: Map<ThreadState, Int>   // all states, counts
)
