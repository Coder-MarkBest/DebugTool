package com.debugtools.stability

import android.content.Context
import android.os.DropBoxManager
import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.scanner.CrashCollector
import com.debugtools.stability.scanner.CrashSource
import com.debugtools.stability.scanner.DropBoxSource
import com.debugtools.stability.scanner.FileSystemSource
import com.debugtools.stability.scanner.ProcessChecker

/**
 * Process-wide entry point for stability monitoring.
 * System-app only — requires access to DropBoxManager and /data/\* directories.
 *
 * ```kotlin
 * StabilityMonitor.init(context, listOf("com.xxx.voice", "com.xxx.asr"))
 * val entries = StabilityMonitor.searchNow()
 * val status  = StabilityMonitor.processAliveStatus()
 * ```
 */
object StabilityMonitor {

    private var processNames: List<String> = emptyList()
    private var dropBoxSource: CrashSource? = null
    private var fileSource: CrashSource? = null
    private var processChecker = ProcessChecker()

    /** Must be called first. Idempotent. */
    fun init(context: Context, names: List<String>) {
        if (processNames.isNotEmpty()) return
        processNames = names
        val dbm = context.applicationContext.getSystemService(Context.DROPBOX_SERVICE) as? DropBoxManager
        dropBoxSource = dbm?.let { DropBoxSource(it) }
        fileSource = FileSystemSource()
    }

    /** Manual full scan: reads all sources, merges, de-dups, filters, returns sorted by time desc. */
    fun searchNow(): List<CrashEntry> {
        val drop = dropBoxSource?.readAll() ?: emptyList()
        val file = fileSource?.readAll() ?: emptyList()
        val merged = CrashCollector.merge(drop, file)
        return CrashCollector.filterByProcess(merged, processNames)
    }

    /** Returns alive status for all monitored process names. */
    fun processAliveStatus(): Map<String, Boolean> = processChecker.check(processNames)
}
