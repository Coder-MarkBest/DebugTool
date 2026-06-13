package com.debugtools.perfmon.data

data class ThreadInfo(
    val tid: Int,
    val name: String,
    val cpuPercent: Float,
    val state: ThreadState
)
