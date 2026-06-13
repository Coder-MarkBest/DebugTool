package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ThreadInfo
import com.debugtools.perfmon.data.ThreadState
import java.io.File

/**
 * Enumerates threads of a process under /proc/<pid>/task/ and computes per-thread
 * top-style CPU% (0..100*coreCount) via differential against the previous successful read.
 *
 * Not thread-safe — call from a single coroutine.
 */
class ThreadReader(
    private val procRoot: File,
    private val coreCount: Int = Runtime.getRuntime().availableProcessors()
) {
    private data class TidBaseline(val tidJiffies: Long, val totalJiffies: Long)
    private val baselines = mutableMapOf<Pair<Int, Int>, TidBaseline>()

    /** Cheap path: just the number of task entries. */
    fun countThreads(pid: Int): Int {
        val taskDir = File(procRoot, "$pid/task")
        return taskDir.listFiles { f -> f.isDirectory }?.size ?: 0
    }

    /** Expensive path: enumerate threads with name, state, CPU%. */
    fun readDetailed(pid: Int): List<ThreadInfo> {
        val taskDir = File(procRoot, "$pid/task")
        val tidDirs = taskDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        val totalJiffies = readTotalJiffies() ?: return emptyList()

        val results = ArrayList<ThreadInfo>(tidDirs.size)
        for (dir in tidDirs) {
            val tid = dir.name.toIntOrNull() ?: continue
            val stat = parseThreadStat(File(dir, "stat")) ?: continue
            val cpuPct = computeCpuPercent(pid, tid, stat.utime + stat.stime, totalJiffies)
            results += ThreadInfo(
                tid = tid,
                name = stat.comm,
                cpuPercent = cpuPct,
                state = stat.state
            )
        }
        return results
    }

    fun forget(pid: Int) {
        baselines.keys.removeAll { it.first == pid }
    }

    private data class ThreadStat(val comm: String, val state: ThreadState, val utime: Long, val stime: Long)

    private fun parseThreadStat(statFile: File): ThreadStat? {
        return try {
            if (!statFile.exists()) return null
            val raw = statFile.readText()
            val rparen = raw.lastIndexOf(')')
            if (rparen < 0) return null
            val comm = raw.substring(raw.indexOf('(') + 1, rparen)
            val rest = raw.substring(rparen + 2).trim().split(Regex("\\s+"))
            val state = ThreadState.fromCode(rest[0].firstOrNull() ?: '?')
            val utime = rest[11].toLong()
            val stime = rest[12].toLong()
            ThreadStat(comm, state, utime, stime)
        } catch (_: Exception) {
            null
        }
    }

    private fun readTotalJiffies(): Long? {
        return try {
            val statFile = File(procRoot, "stat")
            if (!statFile.exists()) return null
            val firstLine = statFile.bufferedReader().use { it.readLine() } ?: return null
            firstLine.trim().split(Regex("\\s+"))
                .drop(1).take(10).sumOf { it.toLong() }
        } catch (_: Exception) {
            null
        }
    }

    private fun computeCpuPercent(pid: Int, tid: Int, tidJiffies: Long, totalJiffies: Long): Float {
        val key = pid to tid
        val prev = baselines[key]
        baselines[key] = TidBaseline(tidJiffies, totalJiffies)
        if (prev == null) return 0f
        val tidDelta = tidJiffies - prev.tidJiffies
        val totalDelta = totalJiffies - prev.totalJiffies
        if (totalDelta <= 0L) return 0f
        return (tidDelta.toFloat() / totalDelta.toFloat()) * 100f * coreCount
    }
}
