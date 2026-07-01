package com.debugtools.startup.store

import com.debugtools.startup.protocol.StartupSession
import org.json.JSONObject
import java.io.File

/**
 * Persists startup sessions as JSON under [dir] (the host passes context.filesDir/startup,
 * which Android removes on uninstall). Keeps the [maxSessions] most recent files.
 */
class StartupStore(
    private val dir: File,
    private val maxSessions: Int = 10
) {
    fun save(session: StartupSession) {
        dir.mkdirs()
        val safeId = session.sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val name = "%013d_%s.json".format(session.startedAtWallMs, safeId)
        File(dir, name).writeText(session.toJson().toString())
        evict()
    }

    /** Most-recent first. Unparseable files are skipped. */
    fun load(): List<StartupSession> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedByDescending { it.name }
            .mapNotNull { runCatching { StartupSession.fromJson(JSONObject(it.readText())) }.getOrNull() }
    }

    private fun evict() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        files.sortedByDescending { it.name }
            .drop(maxSessions)
            .forEach { it.delete() }
    }
}
