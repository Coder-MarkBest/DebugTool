package com.debugtools.okhttp.view.widget

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Detects JSON content (via Content-Type header or body sniff) and pretty-prints
 * with 2-space indentation. Returns null if the body is not JSON or malformed.
 */
object JsonPrettyPrinter {

    fun tryFormat(body: String, contentType: String?): String? {
        if (!looksLikeJson(body, contentType)) return null
        return try {
            when (val parsed = JSONTokener(body).nextValue()) {
                is JSONObject -> parsed.toString(2)
                is JSONArray -> parsed.toString(2)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeJson(body: String, contentType: String?): Boolean {
        if (contentType != null && contentType.contains("json", ignoreCase = true)) return true
        if (contentType != null) return false
        val trimmed = body.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }
}
