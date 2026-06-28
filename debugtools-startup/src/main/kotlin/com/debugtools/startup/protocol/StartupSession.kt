package com.debugtools.startup.protocol

import org.json.JSONArray
import org.json.JSONObject

/** One full app-launch session: t0 = process start, plus the steps and a completion marker. */
data class StartupSession(
    val sessionId: String,
    val startedAtWallMs: Long,
    val launchUptimeMs: Long,
    val appVersion: String?,
    val steps: List<StartupStep>,
    val completedUptimeMs: Long?,
    val completedExplicitly: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("startedAtWallMs", startedAtWallMs)
        put("launchUptimeMs", launchUptimeMs)
        put("appVersion", appVersion ?: JSONObject.NULL)
        put("completedUptimeMs", completedUptimeMs ?: JSONObject.NULL)
        put("completedExplicitly", completedExplicitly)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): StartupSession {
            val arr = o.getJSONArray("steps")
            val steps = (0 until arr.length()).map { StartupStep.fromJson(arr.getJSONObject(it)) }
            return StartupSession(
                sessionId = o.getString("sessionId"),
                startedAtWallMs = o.getLong("startedAtWallMs"),
                launchUptimeMs = o.getLong("launchUptimeMs"),
                appVersion = if (o.isNull("appVersion")) null else o.getString("appVersion"),
                steps = steps,
                completedUptimeMs = if (o.isNull("completedUptimeMs")) null else o.getLong("completedUptimeMs"),
                completedExplicitly = o.getBoolean("completedExplicitly")
            )
        }
    }
}
