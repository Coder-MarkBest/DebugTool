package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry

/** Pure functions over crash entries — merge, dedup, sort, filter. */
object CrashCollector {

    private const val DEDUP_TRACE_LEN = 200

    /**
     * Merge two source result lists into one de-duplicated, timestamp-descending list.
     * Dedup key: (timestamp, processName, stackTrace first [DEDUP_TRACE_LEN] chars).
     * When a duplicate exists, prefers the DropBox entry (processed first).
     */
    fun merge(dropBoxEntries: List<CrashEntry>, fileEntries: List<CrashEntry>): List<CrashEntry> {
        val seen = HashSet<String>()
        val result = mutableListOf<CrashEntry>()

        // Process DropBox first (preferred source)
        for (e in (dropBoxEntries + fileEntries).sortedByDescending { it.timestamp }) {
            val key = "${e.timestamp}|${e.processName}|${e.stackTrace.take(DEDUP_TRACE_LEN)}"
            if (seen.add(key)) result.add(e)
        }
        return result
    }

    /** Keep only entries whose [processName] is in [names]. If [names] is empty, return all. */
    fun filterByProcess(entries: List<CrashEntry>, names: List<String>): List<CrashEntry> {
        if (names.isEmpty()) return entries
        val nameSet = names.toSet()
        return entries.filter { it.processName in nameSet }
    }
}
