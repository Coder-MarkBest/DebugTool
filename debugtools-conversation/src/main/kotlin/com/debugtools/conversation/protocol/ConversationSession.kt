package com.debugtools.conversation.protocol

import org.json.JSONArray
import org.json.JSONObject

data class ConversationSession(
    val sessionId: String,
    val startedAtWallMs: Long,
    val metadata: Map<String, String>?,
    val turns: List<ConversationTurn>,
    val endedAtWallMs: Long?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("startedAtWallMs", startedAtWallMs)
        put("metadata", if (metadata != null) JSONObject(metadata) else JSONObject.NULL)
        put("turns", JSONArray().apply { turns.forEach { put(it.toJson()) } })
        put("endedAtWallMs", endedAtWallMs ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): ConversationSession {
            val turnsArr = o.getJSONArray("turns")
            val turns = (0 until turnsArr.length()).map { ConversationTurn.fromJson(turnsArr.getJSONObject(it)) }
            val metaObj = o.optJSONObject("metadata")
            val metadata = if (metaObj != null && !o.isNull("metadata")) {
                metaObj.keys().asSequence().associateWith { metaObj.getString(it) }
            } else null
            return ConversationSession(
                sessionId = o.getString("sessionId"),
                startedAtWallMs = o.getLong("startedAtWallMs"),
                metadata = metadata,
                turns = turns,
                endedAtWallMs = if (o.isNull("endedAtWallMs")) null else o.getLong("endedAtWallMs")
            )
        }
    }
}
