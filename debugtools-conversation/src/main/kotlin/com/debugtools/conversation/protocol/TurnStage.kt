package com.debugtools.conversation.protocol

import org.json.JSONObject

data class TurnStage(
    val name: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long?,
    val status: StageStatus,
    val input: String?,
    val output: String?,
    val error: String?,
    val thread: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("startOffsetMs", startOffsetMs)
        put("endOffsetMs", endOffsetMs ?: JSONObject.NULL)
        put("status", status.name)
        put("input", input ?: JSONObject.NULL)
        put("output", output ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("thread", thread)
    }

    companion object {
        fun fromJson(o: JSONObject): TurnStage = TurnStage(
            name = o.getString("name"),
            startOffsetMs = o.getLong("startOffsetMs"),
            endOffsetMs = if (o.isNull("endOffsetMs")) null else o.getLong("endOffsetMs"),
            status = StageStatus.valueOf(o.getString("status")),
            input = if (o.isNull("input")) null else o.getString("input"),
            output = if (o.isNull("output")) null else o.getString("output"),
            error = if (o.isNull("error")) null else o.getString("error"),
            thread = o.getString("thread")
        )
    }
}
