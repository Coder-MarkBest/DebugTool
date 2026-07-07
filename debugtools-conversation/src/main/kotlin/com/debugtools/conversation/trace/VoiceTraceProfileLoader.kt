package com.debugtools.conversation.trace

import org.json.JSONArray
import org.json.JSONObject

interface VoiceTraceProfileLoader {
    fun load(json: JSONObject): VoiceTraceProfile
}

class JsonVoiceTraceProfileLoader : VoiceTraceProfileLoader {
    override fun load(json: JSONObject): VoiceTraceProfile {
        val boundaryJson = json.optJSONObject("boundary") ?: JSONObject()
        return VoiceTraceProfile(
            requestKey = json.optString("requestKey", "requestId"),
            boundary = RequestBoundaryRule(
                startEvents = boundaryJson.optStringArray("startEvents"),
                exitEvents = boundaryJson.optStringArray("exitEvents"),
                fallbackTimeoutMs = boundaryJson.optLong("fallbackTimeoutMs", 30_000L)
            ),
            stageRules = json.optJSONArray("stages").objects().map { item ->
                StageRule(
                    id = item.getString("id"),
                    begin = item.getString("begin"),
                    end = item.getString("end"),
                    label = item.optString("label", item.getString("id")),
                    category = TraceCategory.valueOf(item.optString("category", "CUSTOM")),
                    showInConversation = item.optBoolean("showInConversation", true),
                    includeInDuration = item.optBoolean("includeInDuration", true),
                    warnIfSlowMs = item.optNullableLong("warnIfSlowMs"),
                    required = item.optBoolean("required", false),
                    order = item.optInt("order", 0)
                )
            },
            markerRules = json.optJSONArray("markers").objects().map { item ->
                MarkerRule(
                    name = item.getString("name"),
                    label = item.optString("label", item.getString("name")),
                    showInConversation = item.optBoolean("showInConversation", true),
                    includeInDuration = item.optBoolean("includeInDuration", false),
                    category = TraceCategory.valueOf(item.optString("category", "CUSTOM")),
                    order = item.optInt("order", 0)
                )
            }
        )
    }
}

private fun JSONObject.optStringArray(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).map { array.getString(it) }
}

private fun JSONObject.optNullableLong(name: String): Long? =
    if (has(name) && !isNull(name)) getLong(name) else null

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).map { getJSONObject(it) }
}
