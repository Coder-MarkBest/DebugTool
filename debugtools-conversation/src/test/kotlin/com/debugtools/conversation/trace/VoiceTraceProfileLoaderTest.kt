package com.debugtools.conversation.trace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceProfileLoaderTest {
    @Test
    fun `json loader uses the same fields as dsl`() {
        val json = JSONObject(
            """
            {
              "requestKey": "requestId",
              "boundary": {
                "startEvents": ["vadBegin"],
                "exitEvents": ["RequestExit"],
                "fallbackTimeoutMs": 45000
              },
              "stages": [
                {
                  "id": "NLU",
                  "begin": "NluBegin",
                  "end": "NluEnd",
                  "label": "NLU",
                  "category": "NLU",
                  "showInConversation": true,
                  "includeInDuration": true,
                  "warnIfSlowMs": 500,
                  "required": true,
                  "order": 30
                }
              ],
              "markers": [
                {
                  "name": "debugCacheHit",
                  "label": "Cache hit",
                  "showInConversation": false,
                  "includeInDuration": false,
                  "category": "CUSTOM",
                  "order": 99
                }
              ]
            }
            """.trimIndent()
        )

        val profile = JsonVoiceTraceProfileLoader().load(json)

        assertEquals("requestId", profile.requestKey)
        assertEquals(listOf("vadBegin"), profile.boundary.startEvents)
        assertEquals(listOf("RequestExit"), profile.boundary.exitEvents)
        assertEquals(45_000L, profile.boundary.fallbackTimeoutMs)
        assertEquals("NLU", profile.stageRules.single().id)
        assertEquals(TraceCategory.NLU, profile.stageRules.single().category)
        assertTrue(profile.stageRules.single().required)
        assertEquals("debugCacheHit", profile.markerRules.single().name)
        assertFalse(profile.markerRules.single().showInConversation)
    }
}
