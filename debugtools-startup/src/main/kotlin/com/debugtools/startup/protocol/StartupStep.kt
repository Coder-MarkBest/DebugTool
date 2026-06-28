package com.debugtools.startup.protocol

import org.json.JSONArray
import org.json.JSONObject

/** One component's initialization. [name] is unique within a session. Times are SystemClock.uptimeMillis(). */
data class StartupStep(
    val name: String,
    val dependsOn: List<String>,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,
    val status: StepStatus,
    val error: String?,
    val thread: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("dependsOn", JSONArray(dependsOn))
        put("startUptimeMs", startUptimeMs)
        put("endUptimeMs", endUptimeMs ?: JSONObject.NULL)
        put("status", status.name)
        put("error", error ?: JSONObject.NULL)
        put("thread", thread)
    }

    companion object {
        fun fromJson(o: JSONObject): StartupStep {
            val depsArr = o.getJSONArray("dependsOn")
            val deps = (0 until depsArr.length()).map { depsArr.getString(it) }
            return StartupStep(
                name = o.getString("name"),
                dependsOn = deps,
                startUptimeMs = o.getLong("startUptimeMs"),
                endUptimeMs = if (o.isNull("endUptimeMs")) null else o.getLong("endUptimeMs"),
                status = StepStatus.valueOf(o.getString("status")),
                error = if (o.isNull("error")) null else o.getString("error"),
                thread = o.getString("thread")
            )
        }
    }
}
