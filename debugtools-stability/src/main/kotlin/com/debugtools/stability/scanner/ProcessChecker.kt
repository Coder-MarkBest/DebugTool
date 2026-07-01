package com.debugtools.stability.scanner

import java.io.File

/**
 * Checks whether named processes are alive by scanning /proc/<pid>/cmdline.
 * [procDir] is injectable for testing (default = "/proc").
 */
class ProcessChecker(private val procDir: File = File("/proc")) {

    /**
     * Returns a map of each requested process name -> alive status.
     * A process is considered alive if /proc/<pid>/cmdline starts with the name.
     */
    fun check(processNames: List<String>): Map<String, Boolean> {
        val alive = mutableSetOf<String>()
        val pidDirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()
        for (pidDir in pidDirs) {
            val cmdline = try {
                String(File(pidDir, "cmdline").readBytes())
                    .replace('\u0000', ' ')  // null-separated -> space-separated
                    .trim()
            } catch (_: Exception) { continue }
            val name = cmdline.substringBefore(' ')
            if (name.isNotEmpty()) alive.add(name)
        }
        return processNames.associateWith { it in alive }
    }
}
