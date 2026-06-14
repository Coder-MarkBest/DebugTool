package com.debugtools.perfmon.source

import java.io.File

/**
 * Computes per-process top-style CPU% (0..100*coreCount) by reading
 * /proc/<pid>/stat (utime+stime) and /proc/stat (total system jiffies) and
 * taking the delta against the previous successful read.
 *
 * When /proc/stat is unreadable (SELinux denies non-system apps on Android 12+),
 * falls back to a wall-clock denominator: CPU% = (jiffies_delta / USER_HZ) /
 * wall_seconds_delta * 100. Result is still top-style (one fully-busy core = 100%).
 *
 * Returns null until a second sample is available, or when the pid is missing.
 *
 * Not thread-safe — caller is expected to call from a single coroutine.
 *
 * [procRoot] is normally `File("/proc")` but can be substituted in tests with a
 * TemporaryFolder containing fake `<pid>/stat` files and a top-level `stat` file.
 */
class ProcStatReader(
    private val procRoot: File,
    private val coreCount: Int = Runtime.getRuntime().availableProcessors(),
    private val clockNanos: () -> Long = System::nanoTime
) {
    private data class Baseline(
        val processJiffies: Long,
        val totalJiffies: Long?,   // null when /proc/stat unavailable
        val wallNanos: Long
    )

    private val baselines = mutableMapOf<Int, Baseline>()

    fun read(pid: Int): Float? {
        val processJiffies = readProcessJiffies(pid) ?: return null
        val totalJiffies = readTotalJiffies()  // null on SELinux denial
        val nowNanos = clockNanos()

        val prev = baselines[pid]
        baselines[pid] = Baseline(processJiffies, totalJiffies, nowNanos)
        if (prev == null) return null

        val processDelta = processJiffies - prev.processJiffies

        // Prefer /proc/stat denominator when both samples have it; otherwise fall
        // back to wall clock (works without the SELinux proc_stat permission).
        return if (totalJiffies != null && prev.totalJiffies != null) {
            val totalDelta = totalJiffies - prev.totalJiffies
            if (totalDelta <= 0L) null
            else (processDelta.toFloat() / totalDelta.toFloat()) * 100f * coreCount
        } else {
            val wallNanosDelta = nowNanos - prev.wallNanos
            if (wallNanosDelta <= 0L) null
            else {
                val processSeconds = processDelta.toFloat() / USER_HZ
                val wallSeconds = wallNanosDelta / 1_000_000_000f
                (processSeconds / wallSeconds) * 100f
            }
        }
    }

    /** Drops the baseline so this pid is re-baselined on next read (e.g. after restart). */
    fun forget(pid: Int) {
        baselines.remove(pid)
    }

    private fun readProcessJiffies(pid: Int): Long? {
        return try {
            val statFile = File(procRoot, "$pid/stat")
            if (!statFile.exists()) return null
            // Parse field 14 (utime) + field 15 (stime). The comm field may contain
            // spaces, so find the closing paren and split from there.
            val raw = statFile.readText()
            val rparen = raw.lastIndexOf(')')
            if (rparen < 0) return null
            // After ')', fields are: state(3) ppid(4) pgrp(5) session(6) tty_nr(7)
            // tpgid(8) flags(9) minflt(10) cminflt(11) majflt(12) cmajflt(13) utime(14) stime(15)
            val rest = raw.substring(rparen + 2).trim().split(Regex("\\s+"))
            val utime = rest[11].toLong()
            val stime = rest[12].toLong()
            utime + stime
        } catch (_: Exception) {
            null
        }
    }

    private fun readTotalJiffies(): Long? {
        return try {
            // Avoid File.exists() — under SELinux denial it returns false but throws
            // a SecurityException on read, both of which the catch below handles.
            val statFile = File(procRoot, "stat")
            // first line: cpu  user nice system idle iowait irq softirq steal guest guest_nice
            val firstLine = statFile.bufferedReader().use { it.readLine() } ?: return null
            firstLine.trim().split(Regex("\\s+"))
                .drop(1)         // drop "cpu"
                .take(10)        // take all numeric fields
                .sumOf { it.toLong() }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        // sysconf(_SC_CLK_TCK); Android kernels are built with CONFIG_HZ=100.
        const val USER_HZ = 100L
    }
}
