package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ProcessTarget
import java.io.File

/**
 * Resolves a [ProcessTarget] to a live pid by scanning [procRoot] for numeric
 * directories and matching the first segment of each `cmdline` (null-separated).
 *
 * ByName resolution picks the first matching pid (typically the only one for
 * application processes). Re-running resolve picks up a new pid if the process
 * was restarted with a different pid.
 */
class ProcDiscoverer(private val procRoot: File) {

    fun resolve(target: ProcessTarget): Int? = when (target) {
        is ProcessTarget.ByPid -> if (isAlive(target.pid)) target.pid else null
        is ProcessTarget.ByName -> findPidByName(target.processName)
    }

    private fun isAlive(pid: Int): Boolean = File(procRoot, pid.toString()).exists()

    private fun findPidByName(name: String): Int? {
        val children = procRoot.listFiles() ?: return null
        for (dir in children) {
            if (!dir.isDirectory) continue
            val pid = dir.name.toIntOrNull() ?: continue
            val cmdlineFile = File(dir, "cmdline")
            if (!cmdlineFile.exists()) continue
            try {
                val bytes = cmdlineFile.readBytes()
                val end = bytes.indexOf(0x00.toByte()).let { if (it < 0) bytes.size else it }
                val firstSeg = String(bytes, 0, end)
                if (firstSeg == name) return pid
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
