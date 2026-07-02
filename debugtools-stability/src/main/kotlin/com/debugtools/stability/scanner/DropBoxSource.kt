package com.debugtools.stability.scanner

import android.os.Build
import android.os.DropBoxManager
import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.protocol.CrashType

/**
 * Reads crash entries from [DropBoxManager].
 * Tags queried: system_app_crash → JAVA_CRASH, system_app_anr → ANR,
 * SYSTEM_TOMBSTONE → NATIVE_CRASH, SYSTEM_NATIVE_CRASH → NATIVE_CRASH (API 31+).
 */
class DropBoxSource(private val dropBox: DropBoxManager) : CrashSource {

    private val tagMap = mapOf(
        "system_app_crash" to CrashType.JAVA_CRASH,
        "system_app_anr" to CrashType.ANR,
        "SYSTEM_TOMBSTONE" to CrashType.NATIVE_CRASH
    )

    override fun readAll(): List<CrashEntry> {
        val entries = mutableListOf<CrashEntry>()
        for ((tag, type) in tagMap) {
            var nextTime = 0L
            try {
                while (true) {
                    val entry = dropBox.getNextEntry(tag, nextTime) ?: break
                    nextTime = entry.timeMillis + 1
                    val text = entry.getText(8192) ?: continue
                    val parsed = parseDropBoxEntry(type, tag, entry, text) ?: continue
                    entries.add(parsed)
                }
            } catch (_: Exception) { /* tag may not exist */ }
        }
        // SYSTEM_NATIVE_CRASH (API 31+)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                var nextTime = 0L
                while (true) {
                    val entry = dropBox.getNextEntry("SYSTEM_NATIVE_CRASH", nextTime) ?: break
                    nextTime = entry.timeMillis + 1
                    val text = entry.getText(8192) ?: continue
                    val parsed = parseDropBoxEntry(CrashType.NATIVE_CRASH, "SYSTEM_NATIVE_CRASH", entry, text) ?: continue
                    entries.add(parsed)
                }
            }
        } catch (_: Exception) {}
        return entries
    }

    private fun parseDropBoxEntry(
        type: CrashType, tag: String, entry: DropBoxManager.Entry, text: String
    ): CrashEntry? {
        val procName = extractProcessName(text).ifEmpty { return null }
        return CrashEntry(
            type = type,
            processName = procName,
            timestamp = entry.timeMillis,
            sourcePath = "DropBox:$tag",
            stackTrace = text,
            pid = extractPid(text)
        )
    }

    /** "Process: com.example.app" → "com.example.app" */
    private fun extractProcessName(text: String): String {
        val match = Regex("Process:\\s*(\\S+)").find(text) ?: return ""
        return match.groupValues[1]
    }

    /** "PID: 12345" → 12345 */
    private fun extractPid(text: String): Int? {
        val match = Regex("PID:\\s*(\\d+)").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
