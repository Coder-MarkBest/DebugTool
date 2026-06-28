package com.debugtools.audiomon.anomaly

import org.json.JSONObject

/** One detected anomaly episode on a stream. [timeMs] is offset from recording start. */
data class AnomalyEvent(
    val stream: StreamId,
    val timeMs: Long,
    val type: AnomalyType,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stream", stream.name)
        put("timeMs", timeMs)
        put("type", type.name)
        put("typeLabel", type.label)
        put("detail", detail)
    }
}
