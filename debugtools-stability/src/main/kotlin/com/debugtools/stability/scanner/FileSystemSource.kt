package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.protocol.CrashType
import java.io.File

/** Scans well-known crash directories on the file system. */
class FileSystemSource : CrashSource {

    private data class DirMapping(val dir: String, val type: CrashType, val filePattern: Regex)

    private val dirs = listOf(
        DirMapping("/data/anr", CrashType.ANR, Regex("anr_|traces_")),
        DirMapping("/data/tombstones", CrashType.NATIVE_CRASH, Regex("tombstone_")),
        DirMapping("/data/system/dropbox", CrashType.JAVA_CRASH, Regex("system_app_crash|SYSTEM_CRASH"))
    )

    override fun readAll(): List<CrashEntry> {
        val entries = mutableListOf<CrashEntry>()
        for (mapping in dirs) {
            val dir = File(mapping.dir)
            if (!dir.canRead()) continue
            dir.listFiles()?.filter { it.isFile && mapping.filePattern.containsMatchIn(it.name) }?.forEach { file ->
                try {
                    val text = file.readText().take(16384) // cap at 16KB per file
                    val procName = CrashTextParser.extractProcessName(text) ?: return@forEach
                    entries.add(CrashEntry(
                        type = mapping.type,
                        processName = procName,
                        timestamp = file.lastModified(),
                        sourcePath = file.absolutePath,
                        stackTrace = text,
                        pid = CrashTextParser.extractPid(text)
                    ))
                } catch (_: Exception) {}
            }
        }
        return entries
    }
}
