package com.debugtools.stability.protocol

data class CrashEntry(
    val type: CrashType,
    val processName: String,
    val timestamp: Long,
    val sourcePath: String,
    val stackTrace: String,
    val pid: Int?
)
