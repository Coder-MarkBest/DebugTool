package com.debugtools.conversation.protocol

import org.json.JSONArray
import org.json.JSONObject

data class ConversationTurn(
    val turnId: String,
    val turnIndex: Int,
    val sessionId: String,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,
    val userInput: String?,
    val stages: List<TurnStage>,
    val outcome: TurnOutcome,
    val tags: List<String>?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("turnId", turnId)
        put("turnIndex", turnIndex)
        put("sessionId", sessionId)
        put("startUptimeMs", startUptimeMs)
        put("endUptimeMs", endUptimeMs ?: JSONObject.NULL)
        put("userInput", userInput ?: JSONObject.NULL)
        put("stages", JSONArray().apply { stages.forEach { put(it.toJson()) } })
        put("outcome", outcome.name)
        put("tags", if (tags != null) JSONArray(tags) else JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): ConversationTurn {
            val stagesArr = o.getJSONArray("stages")
            val stages = (0 until stagesArr.length()).map { TurnStage.fromJson(stagesArr.getJSONObject(it)) }
            val tagsArr = o.optJSONArray("tags")
            val tags = if (tagsArr != null && !o.isNull("tags")) {
                (0 until tagsArr.length()).map { tagsArr.getString(it) }
            } else null
            return ConversationTurn(
                turnId = o.getString("turnId"),
                turnIndex = o.getInt("turnIndex"),
                sessionId = o.getString("sessionId"),
                startUptimeMs = o.getLong("startUptimeMs"),
                endUptimeMs = if (o.isNull("endUptimeMs")) null else o.getLong("endUptimeMs"),
                userInput = if (o.isNull("userInput")) null else o.getString("userInput"),
                stages = stages,
                outcome = TurnOutcome.valueOf(o.getString("outcome")),
                tags = tags
            )
        }
    }
}
