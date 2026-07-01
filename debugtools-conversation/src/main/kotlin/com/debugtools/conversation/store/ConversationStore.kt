package com.debugtools.conversation.store

import com.debugtools.conversation.protocol.ConversationSession
import org.json.JSONObject
import java.io.File

/**
 * Persists conversation sessions as JSON under [dir] (host passes context.filesDir/conversation,
 * which Android removes on uninstall). Keeps the [maxSessions] most recent.
 */
class ConversationStore(
    private val dir: File,
    private val maxSessions: Int = 50
) {
    fun save(session: ConversationSession) {
        dir.mkdirs()
        val safeId = session.sessionId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val name = "%013d_%s.json".format(session.startedAtWallMs, safeId)
        File(dir, name).writeText(session.toJson().toString())
        evict()
    }

    /** Most-recent first. Unparseable files are skipped. */
    fun load(): List<ConversationSession> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedByDescending { it.name }
            .mapNotNull { runCatching { ConversationSession.fromJson(JSONObject(it.readText())) }.getOrNull() }
    }

    private fun evict() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        files.sortedByDescending { it.name }
            .drop(maxSessions)
            .forEach { it.delete() }
    }
}
