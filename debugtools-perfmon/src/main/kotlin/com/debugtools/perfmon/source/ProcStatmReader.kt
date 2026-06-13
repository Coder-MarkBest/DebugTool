package com.debugtools.perfmon.source

import java.io.File

/**
 * Reads /proc/<pid>/statm to compute RSS (resident set size) in bytes.
 * Format: size resident shared text lib data dt  (all in page units)
 */
class ProcStatmReader(
    private val procRoot: File,
    private val pageSize: Int = 4096
) {
    fun readRssBytes(pid: Int): Long? {
        return try {
            val statmFile = File(procRoot, "$pid/statm")
            if (!statmFile.exists()) return null
            val parts = statmFile.readText().trim().split(Regex("\\s+"))
            val residentPages = parts[1].toLong()
            residentPages * pageSize
        } catch (_: Exception) {
            null
        }
    }
}
