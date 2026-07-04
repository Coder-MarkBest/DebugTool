package com.debugtools.stability.scanner

object CrashTextParser {
    private val javaProcess = Regex("""Process:\s*(\S+)""")
    private val anrCmdLine = Regex("""Cmd line:\s*(\S+)""")
    private val tombstoneProcess = Regex(""">>>\s*(\S+)\s*<<<""")
    private val pid = Regex("""PID:\s*(\d+)""")

    fun extractProcessName(text: String): String? =
        javaProcess.find(text)?.groupValues?.get(1)
            ?: anrCmdLine.find(text)?.groupValues?.get(1)
            ?: tombstoneProcess.find(text)?.groupValues?.get(1)

    fun extractPid(text: String): Int? =
        pid.find(text)?.groupValues?.get(1)?.toIntOrNull()
}
