# RequestId Recording Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a requestId-first voice trace protocol, global recording workflow, and offline HTML report for DebugTools.

**Architecture:** Add the voice trace protocol to `debugtools-conversation`, use `debugtools-core` for global recording contracts/state, and let modules export recording artifacts through a small `RecordableModule` interface. The UI reads analyzed request data and recording state but does not own trace or recording business logic.

**Tech Stack:** Kotlin, Android Views, Kotlin coroutines, `org.json`, JUnit 4, Robolectric where Android context is needed.

## Global Constraints

- One `requestId` represents one voice request; multi-turn conversation grouping is out of scope.
- Raw trace events must be preserved even when hidden from UI or excluded from duration.
- The first implementation supports Kotlin DSL plus JSON profile loading; YAML is supported later via `VoiceTraceProfileLoader`.
- Exit events may omit `requestId`; active-request fallback must be explicit in issues.
- Only one global recording may be active at a time.
- Recording freezes user interaction but does not stop module sampling or event collection.
- `report.html` must work offline and link to adjacent raw artifacts.
- No cloud upload, PDF report, complex charting library, full Material rewrite, or independent-process audio stream recording.
- Use TDD for behavior changes; run targeted tests first, then `./gradlew test`.
- A connected emulator is available; after implementation, update the sample demo if needed and verify every feasible capability on the emulator.

---

## File Structure

### New Conversation Trace Files

- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceEvent.kt`: raw event model, enums, issue model.
- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceProfile.kt`: profile data classes and DSL builder.
- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceProfileLoader.kt`: JSON loader interface and JSON implementation.
- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceRecorder.kt`: stores raw events, active requests, and request closure state.
- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceAnalyzer.kt`: converts raw request events to timeline, performance, and issues.
- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/VoiceTrace.kt`: public process-wide entry point.

### Modified Conversation UI Files

- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationMonitorModule.kt`: use `VoiceTrace` request store for the new UI while keeping old `ConversationTracer` compatibility.
- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/ConversationRootView.kt`: rename UI around requests and add raw events expansion.

### New Core Recording Files

- `debugtools-core/src/main/kotlin/com/debugtools/core/recording/RecordingModels.kt`: context, state, issue, result, report models.
- `debugtools-core/src/main/kotlin/com/debugtools/core/recording/RecordableModule.kt`: module recording contract.
- `debugtools-core/src/main/kotlin/com/debugtools/core/recording/DebugRecordingManager.kt`: global recording state machine.
- `debugtools-core/src/main/kotlin/com/debugtools/core/recording/HtmlRecordingReportWriter.kt`: offline HTML writer.
- `debugtools-core/src/main/kotlin/com/debugtools/core/recording/JsonFileWriter.kt`: small helper for JSON file output.

### Modified Core UI Files

- `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/FloatingRootView.kt`: wrap expanded view with recording bar and interaction blocker.
- `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/RecordingBarView.kt`: new compact recording bar.
- `debugtools-core/src/main/kotlin/com/debugtools/core/DebugTools.kt`: pass registered modules into recording manager UI.

### Module Export Files

- `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/recording/ConversationRecordingExporter.kt`
- `debugtools-startup/src/main/kotlin/com/debugtools/startup/recording/StartupRecordingExporter.kt`
- `debugtools-okhttp-capture/src/main/kotlin/com/debugtools/okhttp/recording/NetworkCaptureRecordingExporter.kt`
- `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/recording/PerfRecordingExporter.kt`
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/recording/AudioRecordingExporter.kt`
- `debugtools-stability/src/main/kotlin/com/debugtools/stability/recording/StabilityRecordingExporter.kt`

### Stability Fix Files

- `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/CrashTextParser.kt`: shared process/pid parsing.
- `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/DropBoxSource.kt`: use parser.
- `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/FileSystemSource.kt`: use parser.
- `debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityModule.kt`: move scan to presenter-style IO flow.
- `debugtools-stability/src/main/kotlin/com/debugtools/stability/view/StabilityRootView.kt`: render loading, data, empty, permission-limited, and error states.

### Docs

- `docs/INTEGRATION.md`: current module list, permissions, requestId protocol, recording workflow, mode support.

---

## Task 1: Voice Trace Profile DSL and JSON Loader

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceEvent.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceProfile.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceProfileLoader.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/trace/VoiceTraceProfileTest.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/trace/VoiceTraceProfileLoaderTest.kt`

**Interfaces:**
- Produces: `VoiceTraceEvent`, `TraceEventType`, `TraceOutcome`, `TraceCategory`, `VoiceTraceProfile`, `VoiceTraceProfileBuilder`, `VoiceTraceProfileLoader`.
- Consumes: `org.json.JSONObject` for JSON profile loading.

- [ ] **Step 1: Write failing DSL tests**

Create `VoiceTraceProfileTest.kt`:

```kotlin
package com.debugtools.conversation.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceProfileTest {
    @Test fun `dsl builds stage marker and boundary rules`() {
        val profile = voiceTraceProfile {
            requestKey = "requestId"
            requestBoundary {
                startEvents = listOf("vadBegin", "AsrBegin")
                exitEvents = listOf("DialogExit")
                fallbackTimeoutMs = 30_000
            }
            stage("ASR") {
                begin = "AsrBegin"
                end = "AsrEnd"
                label = "ASR"
                category = TraceCategory.ASR
                showInConversation = true
                includeInDuration = true
                warnIfSlowMs = 800
                required = true
                order = 20
            }
            marker("internalCacheHit") {
                showInConversation = false
                includeInDuration = false
            }
        }

        assertEquals("requestId", profile.requestKey)
        assertEquals(listOf("vadBegin", "AsrBegin"), profile.boundary.startEvents)
        assertEquals(listOf("DialogExit"), profile.boundary.exitEvents)
        assertEquals(30_000L, profile.boundary.fallbackTimeoutMs)

        val asr = profile.stageRules.single()
        assertEquals("ASR", asr.id)
        assertEquals("AsrBegin", asr.begin)
        assertEquals("AsrEnd", asr.end)
        assertEquals(TraceCategory.ASR, asr.category)
        assertTrue(asr.showInConversation)
        assertTrue(asr.includeInDuration)
        assertTrue(asr.required)
        assertEquals(20, asr.order)

        val marker = profile.markerRules.single()
        assertEquals("internalCacheHit", marker.name)
        assertFalse(marker.showInConversation)
        assertFalse(marker.includeInDuration)
    }
}
```

- [ ] **Step 2: Run DSL test to verify RED**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.trace.VoiceTraceProfileTest`

Expected: FAIL with unresolved references such as `voiceTraceProfile` and `TraceCategory`.

- [ ] **Step 3: Implement profile models and DSL**

Create `VoiceTraceEvent.kt`:

```kotlin
package com.debugtools.conversation.trace

enum class TraceEventType { BEGIN, END, INSTANT, ERROR }
enum class TraceOutcome { SUCCESS, FAILED, TIMEOUT, ABORTED }
enum class TraceCategory { VAD, ASR, NLU, DM, TTS, TOOL, NETWORK, CUSTOM }
enum class TraceIssueSeverity { CRITICAL, WARNING, INFO }

data class VoiceTraceEvent(
    val requestId: String?,
    val name: String,
    val type: TraceEventType,
    val timestampUptimeMs: Long,
    val wallTimeMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, String> = emptyMap()
)

data class TraceIssue(
    val severity: TraceIssueSeverity,
    val type: String,
    val detail: String,
    val stageId: String? = null
)
```

Create `VoiceTraceProfile.kt`:

```kotlin
package com.debugtools.conversation.trace

data class RequestBoundaryRule(
    val startEvents: List<String> = emptyList(),
    val exitEvents: List<String> = emptyList(),
    val fallbackTimeoutMs: Long = 30_000L
)

data class StageRule(
    val id: String,
    val begin: String,
    val end: String,
    val label: String,
    val category: TraceCategory,
    val showInConversation: Boolean,
    val includeInDuration: Boolean,
    val warnIfSlowMs: Long?,
    val required: Boolean,
    val order: Int
)

data class MarkerRule(
    val name: String,
    val label: String,
    val showInConversation: Boolean,
    val includeInDuration: Boolean,
    val category: TraceCategory,
    val order: Int
)

data class VoiceTraceProfile(
    val requestKey: String = "requestId",
    val boundary: RequestBoundaryRule = RequestBoundaryRule(),
    val stageRules: List<StageRule> = emptyList(),
    val markerRules: List<MarkerRule> = emptyList()
) {
    fun isExitEvent(name: String): Boolean = name in boundary.exitEvents
    fun markerFor(name: String): MarkerRule? = markerRules.firstOrNull { it.name == name }
}

class VoiceTraceProfileBuilder {
    var requestKey: String = "requestId"
    private var boundary = RequestBoundaryRule()
    private val stages = mutableListOf<StageRule>()
    private val markers = mutableListOf<MarkerRule>()

    fun requestBoundary(block: RequestBoundaryBuilder.() -> Unit) {
        boundary = RequestBoundaryBuilder().apply(block).build()
    }

    fun stage(id: String, block: StageRuleBuilder.() -> Unit) {
        stages += StageRuleBuilder(id).apply(block).build()
    }

    fun marker(name: String, block: MarkerRuleBuilder.() -> Unit) {
        markers += MarkerRuleBuilder(name).apply(block).build()
    }

    fun build(): VoiceTraceProfile = VoiceTraceProfile(requestKey, boundary, stages.toList(), markers.toList())
}

class RequestBoundaryBuilder {
    var startEvents: List<String> = emptyList()
    var exitEvents: List<String> = emptyList()
    var fallbackTimeoutMs: Long = 30_000L
    fun build() = RequestBoundaryRule(startEvents, exitEvents, fallbackTimeoutMs)
}

class StageRuleBuilder(private val id: String) {
    var begin: String = ""
    var end: String = ""
    var label: String = id
    var category: TraceCategory = TraceCategory.CUSTOM
    var showInConversation: Boolean = true
    var includeInDuration: Boolean = true
    var warnIfSlowMs: Long? = null
    var required: Boolean = false
    var order: Int = 0

    fun build(): StageRule = StageRule(
        id = id,
        begin = requireNonBlank(begin, "begin"),
        end = requireNonBlank(end, "end"),
        label = label,
        category = category,
        showInConversation = showInConversation,
        includeInDuration = includeInDuration,
        warnIfSlowMs = warnIfSlowMs,
        required = required,
        order = order
    )
}

class MarkerRuleBuilder(private val name: String) {
    var label: String = name
    var showInConversation: Boolean = true
    var includeInDuration: Boolean = false
    var category: TraceCategory = TraceCategory.CUSTOM
    var order: Int = 0

    fun build(): MarkerRule = MarkerRule(
        name = requireNonBlank(name, "name"),
        label = label,
        showInConversation = showInConversation,
        includeInDuration = includeInDuration,
        category = category,
        order = order
    )
}

fun voiceTraceProfile(block: VoiceTraceProfileBuilder.() -> Unit): VoiceTraceProfile =
    VoiceTraceProfileBuilder().apply(block).build()

private fun requireNonBlank(value: String, field: String): String {
    require(value.isNotBlank()) { "$field must not be blank" }
    return value
}
```

- [ ] **Step 4: Run DSL test to verify GREEN**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.trace.VoiceTraceProfileTest`

Expected: PASS.

- [ ] **Step 5: Write failing JSON loader test**

Create `VoiceTraceProfileLoaderTest.kt`:

```kotlin
package com.debugtools.conversation.trace

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceProfileLoaderTest {
    @Test fun `json loader uses the same fields as dsl`() {
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
```

- [ ] **Step 6: Run JSON loader test to verify RED**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.trace.VoiceTraceProfileLoaderTest`

Expected: FAIL with unresolved reference `JsonVoiceTraceProfileLoader`.

- [ ] **Step 7: Implement JSON loader**

Create `VoiceTraceProfileLoader.kt`:

```kotlin
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
                    category = enumValueOf(item.optString("category", "CUSTOM")),
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
                    category = enumValueOf(item.optString("category", "CUSTOM")),
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
```

- [ ] **Step 8: Run profile tests**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests 'com.debugtools.conversation.trace.*Profile*'`

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace \
  debugtools-conversation/src/test/kotlin/com/debugtools/conversation/trace
git commit -m "feat(conversation): add voice trace profile"
```

---

## Task 2: Voice Trace Recorder and Analyzer

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceRecorder.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/trace/VoiceTraceAnalyzer.kt`
- Modify: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/VoiceTrace.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/trace/VoiceTraceAnalyzerTest.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/trace/VoiceTraceRecorderTest.kt`

**Interfaces:**
- Consumes: `VoiceTraceProfile`, `VoiceTraceEvent`, `TraceIssue`.
- Produces: `AnalyzedVoiceRequest`, `VoiceTimelineItem`, `VoiceTraceRecorder.snapshot()`, `VoiceTrace.mark()`.

- [ ] **Step 1: Write failing analyzer tests**

Create `VoiceTraceAnalyzerTest.kt`:

```kotlin
package com.debugtools.conversation.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceAnalyzerTest {
    private val profile = voiceTraceProfile {
        requestBoundary { exitEvents = listOf("RequestExit") }
        marker("vadEnd") {
            label = "VAD End"
            showInConversation = true
            includeInDuration = false
            order = 10
        }
        marker("hidden") {
            showInConversation = false
            includeInDuration = false
            order = 11
        }
        stage("ASR") {
            begin = "AsrBegin"
            end = "AsrEnd"
            label = "ASR"
            category = TraceCategory.ASR
            showInConversation = true
            includeInDuration = true
            warnIfSlowMs = 100
            required = true
            order = 20
        }
    }

    @Test fun `analyzer pairs begin end and keeps hidden raw events`() {
        val events = listOf(
            event("req1", "AsrBegin", TraceEventType.BEGIN, 100),
            event("req1", "hidden", TraceEventType.INSTANT, 120),
            event("req1", "vadEnd", TraceEventType.INSTANT, 130),
            event("req1", "AsrEnd", TraceEventType.END, 260)
        )

        val result = VoiceTraceAnalyzer(profile).analyze("req1", events)

        assertEquals(4, result.rawEvents.size)
        assertEquals(listOf("VAD End", "ASR"), result.timelineItems.map { it.label })
        assertEquals(160L, result.performanceDurationMs)
        assertTrue(result.issues.any { it.type == "SLOW_STAGE" && it.stageId == "ASR" })
        assertTrue(result.timelineItems.none { it.sourceName == "hidden" })
    }

    @Test fun `missing required stage is critical`() {
        val result = VoiceTraceAnalyzer(profile).analyze("req1", listOf(event("req1", "vadEnd", TraceEventType.INSTANT, 100)))

        assertTrue(result.issues.any {
            it.severity == TraceIssueSeverity.CRITICAL && it.type == "REQUIRED_STAGE_MISSING" && it.stageId == "ASR"
        })
    }

    private fun event(requestId: String, name: String, type: TraceEventType, time: Long) =
        VoiceTraceEvent(requestId, name, type, timestampUptimeMs = time, wallTimeMs = time)
}
```

- [ ] **Step 2: Run analyzer tests to verify RED**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.trace.VoiceTraceAnalyzerTest`

Expected: FAIL with unresolved reference `VoiceTraceAnalyzer`.

- [ ] **Step 3: Implement analyzer**

Create `VoiceTraceAnalyzer.kt` with:

```kotlin
package com.debugtools.conversation.trace

data class VoiceTimelineItem(
    val id: String,
    val label: String,
    val sourceName: String,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,
    val durationMs: Long?,
    val includeInDuration: Boolean,
    val category: TraceCategory,
    val order: Int
)

data class AnalyzedVoiceRequest(
    val requestId: String,
    val rawEvents: List<VoiceTraceEvent>,
    val timelineItems: List<VoiceTimelineItem>,
    val performanceDurationMs: Long,
    val issues: List<TraceIssue>
)

class VoiceTraceAnalyzer(private val profile: VoiceTraceProfile) {
    fun analyze(requestId: String, events: List<VoiceTraceEvent>): AnalyzedVoiceRequest {
        val sorted = events.sortedBy { it.timestampUptimeMs }
        val items = mutableListOf<VoiceTimelineItem>()
        val issues = mutableListOf<TraceIssue>()

        for (marker in profile.markerRules) {
            if (!marker.showInConversation) continue
            sorted.filter { it.name == marker.name }.forEach { event ->
                items += VoiceTimelineItem(
                    id = marker.name,
                    label = marker.label,
                    sourceName = marker.name,
                    startUptimeMs = event.timestampUptimeMs,
                    endUptimeMs = null,
                    durationMs = null,
                    includeInDuration = marker.includeInDuration,
                    category = marker.category,
                    order = marker.order
                )
                if (!marker.includeInDuration) {
                    issues += TraceIssue(TraceIssueSeverity.INFO, "MARKER_NOT_COUNTED", "${marker.label} is displayed but excluded from duration", marker.name)
                }
            }
        }

        for (stage in profile.stageRules) {
            val begin = sorted.firstOrNull { it.name == stage.begin }
            val end = sorted.firstOrNull { it.name == stage.end && begin != null && it.timestampUptimeMs >= begin.timestampUptimeMs }
            if (begin == null || end == null) {
                if (stage.required) {
                    issues += TraceIssue(TraceIssueSeverity.CRITICAL, "REQUIRED_STAGE_MISSING", "${stage.label} is missing begin or end", stage.id)
                }
                continue
            }
            val duration = end.timestampUptimeMs - begin.timestampUptimeMs
            if (stage.showInConversation) {
                items += VoiceTimelineItem(stage.id, stage.label, stage.begin, begin.timestampUptimeMs, end.timestampUptimeMs, duration, stage.includeInDuration, stage.category, stage.order)
            }
            val warn = stage.warnIfSlowMs
            if (stage.includeInDuration && warn != null && duration > warn) {
                issues += TraceIssue(TraceIssueSeverity.WARNING, "SLOW_STAGE", "${stage.label} took ${duration}ms > ${warn}ms", stage.id)
            }
        }

        sorted.filter { it.type == TraceEventType.ERROR }.forEach {
            issues += TraceIssue(TraceIssueSeverity.CRITICAL, "ERROR_EVENT", "${it.name} reported error", null)
        }

        val ordered = items.sortedWith(compareBy<VoiceTimelineItem> { it.order }.thenBy { it.startUptimeMs })
        val perf = ordered.filter { it.includeInDuration }.sumOf { it.durationMs ?: 0L }
        return AnalyzedVoiceRequest(requestId, sorted, ordered, perf, issues)
    }
}
```

- [ ] **Step 4: Run analyzer tests to verify GREEN**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.trace.VoiceTraceAnalyzerTest`

Expected: PASS.

- [ ] **Step 5: Write failing recorder exit tests**

Create `VoiceTraceRecorderTest.kt`:

```kotlin
package com.debugtools.conversation.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTraceRecorderTest {
    private val profile = voiceTraceProfile {
        requestBoundary {
            startEvents = listOf("AsrBegin")
            exitEvents = listOf("RequestExit")
            fallbackTimeoutMs = 1_000
        }
    }

    @Test fun `exit with requestId closes that request`() {
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(event("req1", "AsrBegin", TraceEventType.BEGIN, 100))
        recorder.record(event("req1", "RequestExit", TraceEventType.INSTANT, 200))

        val snapshot = recorder.snapshot()

        assertEquals(emptySet<String>(), snapshot.activeRequestIds)
        assertEquals(setOf("req1"), snapshot.closedRequestIds)
        assertTrue(snapshot.issues.isEmpty())
    }

    @Test fun `exit without requestId closes only active request`() {
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(event("req1", "AsrBegin", TraceEventType.BEGIN, 100))
        recorder.record(event(null, "RequestExit", TraceEventType.INSTANT, 200))

        val snapshot = recorder.snapshot()

        assertEquals(emptySet<String>(), snapshot.activeRequestIds)
        assertEquals(setOf("req1"), snapshot.closedRequestIds)
    }

    @Test fun `exit without requestId among multiple active requests warns`() {
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(event("req1", "AsrBegin", TraceEventType.BEGIN, 100))
        recorder.record(event("req2", "AsrBegin", TraceEventType.BEGIN, 150))
        recorder.record(event(null, "RequestExit", TraceEventType.INSTANT, 200))

        val snapshot = recorder.snapshot()

        assertEquals(setOf("req1"), snapshot.activeRequestIds)
        assertEquals(setOf("req2"), snapshot.closedRequestIds)
        assertTrue(snapshot.issues.any { it.type == "EXIT_MATCHED_BY_LATEST_ACTIVE" })
    }

    private fun event(requestId: String?, name: String, type: TraceEventType, time: Long) =
        VoiceTraceEvent(requestId, name, type, timestampUptimeMs = time, wallTimeMs = time)
}
```

- [ ] **Step 6: Run recorder tests to verify RED**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.trace.VoiceTraceRecorderTest`

Expected: FAIL with unresolved reference `VoiceTraceRecorder`.

- [ ] **Step 7: Implement recorder**

Create `VoiceTraceRecorder.kt`:

```kotlin
package com.debugtools.conversation.trace

data class VoiceTraceSnapshot(
    val eventsByRequest: Map<String, List<VoiceTraceEvent>>,
    val orphanEvents: List<VoiceTraceEvent>,
    val activeRequestIds: Set<String>,
    val closedRequestIds: Set<String>,
    val issues: List<TraceIssue>
)

class VoiceTraceRecorder(private val profile: VoiceTraceProfile) {
    private val lock = Any()
    private val eventsByRequest = linkedMapOf<String, MutableList<VoiceTraceEvent>>()
    private val orphanEvents = mutableListOf<VoiceTraceEvent>()
    private val active = linkedSetOf<String>()
    private val closed = linkedSetOf<String>()
    private val lastActiveAt = hashMapOf<String, Long>()
    private val issues = mutableListOf<TraceIssue>()

    fun record(event: VoiceTraceEvent) = synchronized(lock) {
        val requestId = event.requestId
        if (requestId != null) {
            eventsByRequest.getOrPut(requestId) { mutableListOf() }.add(event)
            active += requestId
            lastActiveAt[requestId] = event.timestampUptimeMs
            if (profile.isExitEvent(event.name)) close(requestId)
            return
        }

        if (profile.isExitEvent(event.name)) {
            closeWithoutRequestId(event)
        } else {
            orphanEvents += event
        }
    }

    fun snapshot(): VoiceTraceSnapshot = synchronized(lock) {
        VoiceTraceSnapshot(
            eventsByRequest = eventsByRequest.mapValues { it.value.toList() },
            orphanEvents = orphanEvents.toList(),
            activeRequestIds = active.toSet(),
            closedRequestIds = closed.toSet(),
            issues = issues.toList()
        )
    }

    private fun close(requestId: String) {
        active -= requestId
        closed += requestId
    }

    private fun closeWithoutRequestId(event: VoiceTraceEvent) {
        when (active.size) {
            0 -> {
                orphanEvents += event
                issues += TraceIssue(TraceIssueSeverity.WARNING, "ORPHAN_EXIT_EVENT", "${event.name} had no active request")
            }
            1 -> close(active.first())
            else -> {
                val latest = active.maxBy { lastActiveAt[it] ?: Long.MIN_VALUE }
                close(latest)
                issues += TraceIssue(TraceIssueSeverity.WARNING, "EXIT_MATCHED_BY_LATEST_ACTIVE", "${event.name} had no requestId; matched $latest")
            }
        }
    }
}
```

- [ ] **Step 8: Add public VoiceTrace entry point**

Create `VoiceTrace.kt`:

```kotlin
package com.debugtools.conversation

import android.content.Context
import android.os.SystemClock
import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.TraceOutcome
import com.debugtools.conversation.trace.VoiceTraceEvent
import com.debugtools.conversation.trace.VoiceTraceProfile
import com.debugtools.conversation.trace.VoiceTraceRecorder

object VoiceTrace {
    @Volatile private var recorder: VoiceTraceRecorder? = null
    @Volatile private var profile: VoiceTraceProfile? = null

    fun init(context: Context, profile: VoiceTraceProfile) {
        this.profile = profile
        this.recorder = VoiceTraceRecorder(profile)
    }

    fun mark(event: VoiceTraceEvent) {
        recorder?.record(event)
    }

    fun begin(requestId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(VoiceTraceEvent(requestId, name, TraceEventType.BEGIN, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun end(requestId: String, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(VoiceTraceEvent(requestId, name, TraceEventType.END, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun instant(requestId: String? = null, name: String, attrs: Map<String, String> = emptyMap()) {
        mark(VoiceTraceEvent(requestId, name, TraceEventType.INSTANT, SystemClock.uptimeMillis(), attributes = attrs))
    }

    fun finish(requestId: String? = null, outcome: TraceOutcome = TraceOutcome.SUCCESS) {
        instant(requestId, "VoiceTraceFinish", mapOf("outcome" to outcome.name))
    }

    internal fun snapshot() = recorder?.snapshot()
    internal fun currentProfile() = profile
}
```

Before moving on, update `finish()` to use a configured exit event if present:

```kotlin
fun finish(requestId: String? = null, outcome: TraceOutcome = TraceOutcome.SUCCESS) {
    val exit = profile?.boundary?.exitEvents?.firstOrNull() ?: "VoiceTraceFinish"
    instant(requestId, exit, mapOf("outcome" to outcome.name))
}
```

- [ ] **Step 9: Run trace tests**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests 'com.debugtools.conversation.trace.*'`

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation \
  debugtools-conversation/src/test/kotlin/com/debugtools/conversation/trace
git commit -m "feat(conversation): add voice trace analyzer"
```

---

## Task 3: Core Recording Contracts and State Machine

**Files:**
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/recording/RecordingModels.kt`
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/recording/RecordableModule.kt`
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/recording/DebugRecordingManager.kt`
- Test: `debugtools-core/src/test/kotlin/com/debugtools/core/recording/DebugRecordingManagerTest.kt`

**Interfaces:**
- Produces: `RecordableModule`, `RecordingContext`, `ModuleRecordingResult`, `RecordingSessionReport`, `DebugRecordingManager.start()`, `DebugRecordingManager.stop()`.
- Consumes: `DebugModule`.

- [ ] **Step 1: Write failing recording manager tests**

Create `DebugRecordingManagerTest.kt`:

```kotlin
package com.debugtools.core.recording

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugRecordingManagerTest {
    @Test fun `start rejects nested recording`() {
        val manager = DebugRecordingManager()
        val root = createTempDir()
        manager.start(emptyList(), root)

        val error = runCatching { manager.start(emptyList(), root) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test fun `stop survives module export failure`() {
        val manager = DebugRecordingManager()
        val root = createTempDir()
        manager.start(listOf(FailingRecordableModule()), root)

        val report = manager.stop()

        assertEquals(1, report.moduleResults.size)
        assertTrue(report.issues.any { it.type == "MODULE_EXPORT_FAILED" })
        assertTrue(report.rootDir.exists())
    }

    private class FailingRecordableModule : DebugModule, RecordableModule {
        override val moduleId = "failing"
        override val tabTitle = "Failing"
        override val recorderId = "failing"
        override fun createContentView(context: Context): View = View(context)
        override fun buildSettings(): List<SettingGroup> = emptyList()
        override fun getBriefItems(): List<BriefItem> = emptyList()
        override fun onAttach(context: Context, storage: SettingsStorage) {}
        override fun onDetach() {}
        override fun onRecordingStart(context: RecordingContext) = ModuleRecordingSnapshot(moduleId)
        override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
            error("boom")
        }
    }
}
```

- [ ] **Step 2: Run recording tests to verify RED**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.recording.DebugRecordingManagerTest`

Expected: FAIL with unresolved references in `com.debugtools.core.recording`.

- [ ] **Step 3: Implement recording models and manager**

Create `RecordingModels.kt`:

```kotlin
package com.debugtools.core.recording

import java.io.File

enum class RecordingIssueSeverity { CRITICAL, WARNING, INFO }

data class RecordingIssue(
    val severity: RecordingIssueSeverity,
    val type: String,
    val detail: String,
    val moduleId: String? = null
)

data class RecordingContext(
    val recordingId: String,
    val startedAtWallMs: Long,
    val startedAtUptimeMs: Long,
    val rootDir: File
)

data class ModuleRecordingSnapshot(
    val moduleId: String,
    val files: List<File> = emptyList(),
    val summary: Map<String, String> = emptyMap()
)

data class ModuleRecordingResult(
    val moduleId: String,
    val files: List<File> = emptyList(),
    val issues: List<RecordingIssue> = emptyList(),
    val summary: Map<String, String> = emptyMap()
)

data class RecordingSessionReport(
    val context: RecordingContext,
    val endedAtWallMs: Long,
    val rootDir: File,
    val moduleResults: List<ModuleRecordingResult>,
    val issues: List<RecordingIssue>
)

sealed class RecordingState {
    object Idle : RecordingState()
    data class Active(val context: RecordingContext) : RecordingState()
}
```

Create `RecordableModule.kt`:

```kotlin
package com.debugtools.core.recording

interface RecordableModule {
    val recorderId: String
    fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot
    fun onRecordingStop(context: RecordingContext): ModuleRecordingResult
}
```

Create `DebugRecordingManager.kt`:

```kotlin
package com.debugtools.core.recording

import android.os.SystemClock
import com.debugtools.core.module.DebugModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class DebugRecordingManager {
    private val lock = Any()
    private var activeModules: List<RecordableModule> = emptyList()
    private var activeContext: RecordingContext? = null
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    fun start(modules: List<DebugModule>, recordingsRoot: File): RecordingContext = synchronized(lock) {
        check(activeContext == null) { "Recording already active" }
        val id = recordingId()
        val dir = File(recordingsRoot, id).apply { mkdirs() }
        val context = RecordingContext(id, System.currentTimeMillis(), SystemClock.uptimeMillis(), dir)
        activeModules = modules.filterIsInstance<RecordableModule>()
        activeModules.forEach { runCatching { it.onRecordingStart(context) } }
        activeContext = context
        _state.value = RecordingState.Active(context)
        context
    }

    fun stop(): RecordingSessionReport {
        val context: RecordingContext
        val modules: List<RecordableModule>
        synchronized(lock) {
            context = activeContext ?: error("No active recording")
            modules = activeModules
            activeContext = null
            activeModules = emptyList()
            _state.value = RecordingState.Idle
        }

        val results = mutableListOf<ModuleRecordingResult>()
        val issues = mutableListOf<RecordingIssue>()
        for (module in modules) {
            val result = runCatching { module.onRecordingStop(context) }
            if (result.isSuccess) {
                results += result.getOrThrow()
            } else {
                issues += RecordingIssue(RecordingIssueSeverity.WARNING, "MODULE_EXPORT_FAILED", result.exceptionOrNull()?.message ?: "export failed", module.recorderId)
                results += ModuleRecordingResult(module.recorderId, issues = listOf(issues.last()))
            }
        }
        return RecordingSessionReport(context, System.currentTimeMillis(), context.rootDir, results, issues)
    }

    private fun recordingId(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = (1..4).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
        return "${ts}_$suffix"
    }
}
```

- [ ] **Step 4: Run recording tests to verify GREEN**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.recording.DebugRecordingManagerTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add debugtools-core/src/main/kotlin/com/debugtools/core/recording \
  debugtools-core/src/test/kotlin/com/debugtools/core/recording
git commit -m "feat(core): add global recording manager"
```

---

## Task 4: Conversation Recording Export and Request UI

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/recording/ConversationRecordingExporter.kt`
- Modify: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationMonitorModule.kt`
- Modify: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/ConversationRootView.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/recording/ConversationRecordingExporterTest.kt`

**Interfaces:**
- Consumes: `VoiceTrace.snapshot()`, `VoiceTraceAnalyzer`, `RecordableModule`.
- Produces: `conversation/raw-events.json`, `conversation/requests.json`.

- [ ] **Step 1: Write failing exporter test**

Create `ConversationRecordingExporterTest.kt`:

```kotlin
package com.debugtools.conversation.recording

import com.debugtools.conversation.trace.TraceEventType
import com.debugtools.conversation.trace.VoiceTraceEvent
import com.debugtools.conversation.trace.VoiceTraceRecorder
import com.debugtools.conversation.trace.voiceTraceProfile
import com.debugtools.core.recording.RecordingContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationRecordingExporterTest {
    @Test fun `export writes raw events and analyzed requests`() {
        val profile = voiceTraceProfile {
            stage("ASR") {
                begin = "AsrBegin"
                end = "AsrEnd"
                includeInDuration = true
                showInConversation = true
            }
        }
        val recorder = VoiceTraceRecorder(profile)
        recorder.record(VoiceTraceEvent("req1", "AsrBegin", TraceEventType.BEGIN, 100, 100))
        recorder.record(VoiceTraceEvent("req1", "AsrEnd", TraceEventType.END, 200, 200))
        val dir = createTempDir()
        val context = RecordingContext("r1", 1, 1, dir)

        val result = ConversationRecordingExporter(profile, recorder).export(context)

        assertTrue(File(dir, "conversation/raw-events.json").exists())
        assertTrue(File(dir, "conversation/requests.json").exists())
        assertTrue(result.files.any { it.name == "raw-events.json" })
        assertTrue(result.files.any { it.name == "requests.json" })
    }
}
```

- [ ] **Step 2: Run exporter test to verify RED**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.recording.ConversationRecordingExporterTest`

Expected: FAIL with unresolved reference `ConversationRecordingExporter`.

- [ ] **Step 3: Implement exporter**

Create `ConversationRecordingExporter.kt`:

```kotlin
package com.debugtools.conversation.recording

import com.debugtools.conversation.trace.VoiceTraceAnalyzer
import com.debugtools.conversation.trace.VoiceTraceProfile
import com.debugtools.conversation.trace.VoiceTraceRecorder
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.RecordingContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ConversationRecordingExporter(
    private val profile: VoiceTraceProfile,
    private val recorder: VoiceTraceRecorder
) {
    fun export(context: RecordingContext): ModuleRecordingResult {
        val dir = File(context.rootDir, "conversation").apply { mkdirs() }
        val snapshot = recorder.snapshot()
        val rawFile = File(dir, "raw-events.json")
        val requestsFile = File(dir, "requests.json")
        val analyzer = VoiceTraceAnalyzer(profile)

        rawFile.writeText(JSONObject().apply {
            put("orphanEvents", JSONArray(snapshot.orphanEvents.map { it.toJson() }))
            put("requests", JSONObject().apply {
                snapshot.eventsByRequest.forEach { (id, events) ->
                    put(id, JSONArray(events.map { it.toJson() }))
                }
            })
        }.toString(2))

        requestsFile.writeText(JSONArray(snapshot.eventsByRequest.map { (id, events) ->
            analyzer.analyze(id, events).toJson()
        }).toString(2))

        return ModuleRecordingResult(
            moduleId = "conversation",
            files = listOf(rawFile, requestsFile),
            summary = mapOf("requests" to snapshot.eventsByRequest.size.toString())
        )
    }
}
```

Also add private JSON extensions in the same file:

```kotlin
private fun com.debugtools.conversation.trace.VoiceTraceEvent.toJson() = JSONObject().apply {
    put("requestId", requestId ?: JSONObject.NULL)
    put("name", name)
    put("type", type.name)
    put("timestampUptimeMs", timestampUptimeMs)
    put("wallTimeMs", wallTimeMs)
    put("attributes", JSONObject(attributes))
}

private fun com.debugtools.conversation.trace.AnalyzedVoiceRequest.toJson() = JSONObject().apply {
    put("requestId", requestId)
    put("performanceDurationMs", performanceDurationMs)
    put("timelineItems", JSONArray(timelineItems.map { item ->
        JSONObject().apply {
            put("id", item.id)
            put("label", item.label)
            put("sourceName", item.sourceName)
            put("startUptimeMs", item.startUptimeMs)
            put("endUptimeMs", item.endUptimeMs ?: JSONObject.NULL)
            put("durationMs", item.durationMs ?: JSONObject.NULL)
            put("includeInDuration", item.includeInDuration)
            put("category", item.category.name)
        }
    }))
    put("issues", JSONArray(issues.map {
        JSONObject().apply {
            put("severity", it.severity.name)
            put("type", it.type)
            put("detail", it.detail)
            put("stageId", it.stageId ?: JSONObject.NULL)
        }
    }))
}
```

- [ ] **Step 4: Run exporter test to verify GREEN**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest --tests com.debugtools.conversation.recording.ConversationRecordingExporterTest`

Expected: PASS.

- [ ] **Step 5: Update module and UI**

Modify `ConversationMonitorModule` so it implements `RecordableModule` when `VoiceTrace.currentProfile()` and `VoiceTrace.snapshot()` are available. If not initialized, return a `ModuleRecordingResult` with an info issue `VOICE_TRACE_NOT_INITIALIZED`.

Modify `ConversationRootView` copy:

- Header: `请求链路 · 最近 N 次`
- Detail title: `requestId`
- Add a section `Raw events` that lists event name, type, uptime, and requestId.

- [ ] **Step 6: Run conversation tests**

Run: `./gradlew :debugtools-conversation:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation \
  debugtools-conversation/src/test/kotlin/com/debugtools/conversation
git commit -m "feat(conversation): export request trace recordings"
```

---

## Task 5: HTML Recording Report Writer

**Files:**
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/recording/HtmlRecordingReportWriter.kt`
- Test: `debugtools-core/src/test/kotlin/com/debugtools/core/recording/HtmlRecordingReportWriterTest.kt`

**Interfaces:**
- Consumes: `RecordingSessionReport`.
- Produces: `report.html`.

- [ ] **Step 1: Write failing report writer test**

Create `HtmlRecordingReportWriterTest.kt`:

```kotlin
package com.debugtools.core.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HtmlRecordingReportWriterTest {
    @Test fun `writer includes sections and issue severities`() {
        val dir = createTempDir()
        val context = RecordingContext("rec1", 1000, 10, dir)
        val report = RecordingSessionReport(
            context = context,
            endedAtWallMs = 2000,
            rootDir = dir,
            moduleResults = listOf(ModuleRecordingResult("startup", summary = mapOf("failed" to "1"))),
            issues = listOf(RecordingIssue(RecordingIssueSeverity.CRITICAL, "STEP_FAILED", "asr failed", "startup"))
        )

        val file = HtmlRecordingReportWriter().write(report, File(dir, "report.html"))
        val html = file.readText()

        assertTrue(html.contains("DebugTools Recording Report"))
        assertTrue(html.contains("Voice Requests"))
        assertTrue(html.contains("Startup"))
        assertTrue(html.contains("critical"))
        assertTrue(html.contains("asr failed"))
    }
}
```

- [ ] **Step 2: Run writer test to verify RED**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.recording.HtmlRecordingReportWriterTest`

Expected: FAIL with unresolved reference `HtmlRecordingReportWriter`.

- [ ] **Step 3: Implement report writer**

Create `HtmlRecordingReportWriter.kt`:

```kotlin
package com.debugtools.core.recording

import java.io.File

class HtmlRecordingReportWriter {
    fun write(report: RecordingSessionReport, output: File): File {
        output.parentFile?.mkdirs()
        output.writeText(render(report))
        return output
    }

    private fun render(report: RecordingSessionReport): String {
        val issues = report.issues + report.moduleResults.flatMap { it.issues }
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8">
              <title>DebugTools Recording Report</title>
              <style>
                body { font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif; margin: 0; background: #101418; color: #e6edf3; }
                header { padding: 24px; background: #17202a; border-bottom: 1px solid #2f3b46; }
                main { padding: 20px; display: grid; gap: 16px; }
                section { border: 1px solid #2f3b46; border-radius: 8px; padding: 16px; background: #141a20; }
                h1, h2 { margin: 0 0 12px; }
                table { border-collapse: collapse; width: 100%; }
                th, td { border-bottom: 1px solid #2f3b46; padding: 8px; text-align: left; font-size: 13px; }
                .critical { color: #ff6b6b; }
                .warning { color: #ffd166; }
                .info { color: #8ecae6; }
                .muted { color: #9aa7b2; }
              </style>
            </head>
            <body>
              <header>
                <h1>DebugTools Recording Report</h1>
                <div class="muted">Recording ${escape(report.context.recordingId)} · ${report.endedAtWallMs - report.context.startedAtWallMs}ms</div>
              </header>
              <main>
                ${overview(report, issues)}
                ${artifactSection("Voice Requests")}
                ${artifactSection("Startup")}
                ${artifactSection("Network")}
                ${artifactSection("Performance")}
                ${artifactSection("Audio")}
                ${artifactSection("Stability")}
                ${issuesSection(issues)}
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun overview(report: RecordingSessionReport, issues: List<RecordingIssue>) = """
        <section>
          <h2>Overview</h2>
          <table>
            <tr><th>Saved path</th><td>${escape(report.rootDir.absolutePath)}</td></tr>
            <tr><th>Modules</th><td>${report.moduleResults.size}</td></tr>
            <tr><th>Issues</th><td>${issues.size}</td></tr>
          </table>
        </section>
    """.trimIndent()

    private fun artifactSection(title: String) = """
        <section>
          <h2>${escape(title)}</h2>
          <div class="muted">See adjacent JSON artifacts for raw module data.</div>
        </section>
    """.trimIndent()

    private fun issuesSection(issues: List<RecordingIssue>) = """
        <section>
          <h2>Issues</h2>
          <table>
            <tr><th>Severity</th><th>Module</th><th>Type</th><th>Detail</th></tr>
            ${issues.joinToString("\n") { issue ->
                val cls = issue.severity.name.lowercase()
                "<tr><td class=\"$cls\">$cls</td><td>${escape(issue.moduleId ?: "")}</td><td>${escape(issue.type)}</td><td>${escape(issue.detail)}</td></tr>"
            }}
          </table>
        </section>
    """.trimIndent()

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
```

- [ ] **Step 4: Run writer test to verify GREEN**

Run: `./gradlew :debugtools-core:testDebugUnitTest --tests com.debugtools.core.recording.HtmlRecordingReportWriterTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add debugtools-core/src/main/kotlin/com/debugtools/core/recording/HtmlRecordingReportWriter.kt \
  debugtools-core/src/test/kotlin/com/debugtools/core/recording/HtmlRecordingReportWriterTest.kt
git commit -m "feat(core): generate recording html report"
```

---

## Task 6: Stability Parser and IO Refresh Fix

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/CrashTextParser.kt`
- Modify: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/DropBoxSource.kt`
- Modify: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/FileSystemSource.kt`
- Modify: `debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityModule.kt`
- Modify: `debugtools-stability/src/main/kotlin/com/debugtools/stability/view/StabilityRootView.kt`
- Test: `debugtools-stability/src/test/kotlin/com/debugtools/stability/scanner/CrashTextParserTest.kt`

**Interfaces:**
- Produces: `CrashTextParser.extractProcessName(text)`, `CrashTextParser.extractPid(text)`.
- Consumes: existing `CrashSource` and `StabilityMonitor`.

- [ ] **Step 1: Write failing parser tests**

Create `CrashTextParserTest.kt`:

```kotlin
package com.debugtools.stability.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrashTextParserTest {
    @Test fun `extracts java crash process`() {
        assertEquals("com.voice.app", CrashTextParser.extractProcessName("Process: com.voice.app\nPID: 123"))
    }

    @Test fun `extracts anr cmd line process`() {
        assertEquals("com.voice.asr", CrashTextParser.extractProcessName("Cmd line: com.voice.asr\nBuild fingerprint: x"))
    }

    @Test fun `extracts native tombstone process`() {
        assertEquals("com.voice.native", CrashTextParser.extractProcessName(">>> com.voice.native <<<\nbacktrace:"))
    }

    @Test fun `extracts pid`() {
        assertEquals(123, CrashTextParser.extractPid("PID: 123"))
    }

    @Test fun `returns null when process missing`() {
        assertNull(CrashTextParser.extractProcessName("no process here"))
    }
}
```

- [ ] **Step 2: Run parser tests to verify RED**

Run: `./gradlew :debugtools-stability:testDebugUnitTest --tests com.debugtools.stability.scanner.CrashTextParserTest`

Expected: FAIL with unresolved reference `CrashTextParser`.

- [ ] **Step 3: Implement parser and wire sources**

Create `CrashTextParser.kt`:

```kotlin
package com.debugtools.stability.scanner

object CrashTextParser {
    private val javaProcess = Regex("""Process:\s*(\S+)""")
    private val anrCmdLine = Regex("""Cmd line:\s*(\S+)""")
    private val tombstoneProcess = Regex(""">>>\s*(\S+)\s*<<<""")
    private val pid = Regex("""PID:\s*(\d+)""")

    fun extractProcessName(text: String): String? =
        javaProcess.find(text)?.groupValues?.get(1)
            ?: anrCmdLine.find(text)?.groupValues?.get(1)
            ?: tombstoneProcess.find(text)?.groupValues?.get(1)

    fun extractPid(text: String): Int? =
        pid.find(text)?.groupValues?.get(1)?.toIntOrNull()
}
```

Modify `DropBoxSource` and `FileSystemSource`:

```kotlin
val procName = CrashTextParser.extractProcessName(text) ?: return null
pid = CrashTextParser.extractPid(text)
```

For `FileSystemSource`, use `return@forEach` when parser returns null.

- [ ] **Step 4: Run stability scanner tests**

Run: `./gradlew :debugtools-stability:testDebugUnitTest --tests 'com.debugtools.stability.scanner.*'`

Expected: PASS.

- [ ] **Step 5: Move stability refresh off main**

Modify `StabilityRootView` to expose render methods:

```kotlin
fun renderLoading()
fun renderData(status: Map<String, Boolean>, entries: List<CrashEntry>)
fun renderError(message: String)
```

Modify `StabilityModule` so timer and manual refresh run `StabilityMonitor.searchNow()` on `Dispatchers.IO` and call `rootView.renderData()` on `Dispatchers.Main`.

Use this behavior:

- `createContentView()` calls `refreshAsync()`.
- Button click calls `refreshAsync()`.
- Timer calls `refreshAsync()`.
- If scan throws, render `renderError(e.message ?: "扫描失败")`.

- [ ] **Step 6: Run stability tests and build module**

Run: `./gradlew :debugtools-stability:testDebugUnitTest :debugtools-stability:assembleDebug`

Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability \
  debugtools-stability/src/test/kotlin/com/debugtools/stability
git commit -m "fix(stability): parse crash process formats off main"
```

---

## Task 7: Module Exporters and Recording Bar UI

**Files:**
- Modify module entry points to implement `RecordableModule` where practical.
- Create exporter files listed in File Structure.
- Create: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/RecordingBarView.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/FloatingRootView.kt`
- Modify: `debugtools-core/src/main/kotlin/com/debugtools/core/window/view/ExpandedView.kt`
- Test: targeted module tests where exporter logic is pure.

**Interfaces:**
- Consumes: `RecordableModule`, `DebugRecordingManager`, `HtmlRecordingReportWriter`.
- Produces: recording controls in expanded UI and per-module files.

- [ ] **Step 1: Implement snapshot exporters with minimal JSON**

For each module, export data that already exists in memory or persisted stores:

- conversation: use `ConversationRecordingExporter`.
- startup: write `startup/sessions.json` from `AppStartupMonitor.currentSession()` and `StartupStore.load()` where available.
- okhttp-capture: write repository `snapshot()` to `network/http.json` and `network/websocket.json`.
- perfmon: write repository `state.value` series snapshot to `perfmon/samples.json`.
- audiomon: if no audio session was controlled by global recording, write `audiomon/summary.json` with `recorded=false`.
- stability: write start/stop statuses and crash list to `stability/crashes.json`.

- [ ] **Step 2: Add recording bar view**

Create `RecordingBarView.kt`:

```kotlin
package com.debugtools.core.window.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

internal class RecordingBarView(context: Context) : LinearLayout(context) {
    private val status = TextView(context)
    private val stop = TextView(context)
    var onStopClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setPadding(16, 10, 16, 10)
        setBackgroundColor(Color.parseColor("#1E2A32"))
        status.setTextColor(Color.WHITE)
        status.textSize = 12f
        status.typeface = Typeface.DEFAULT_BOLD
        stop.setTextColor(Color.parseColor("#FFB86B"))
        stop.textSize = 12f
        stop.text = "停止录制"
        stop.setOnClickListener { onStopClick?.invoke() }
        addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(stop, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun render(text: String) {
        status.text = text
    }
}
```

- [ ] **Step 3: Wire expanded recording UI**

Modify `FloatingRootView` so expanded mode contains:

- recording bar at top when `DebugRecordingManager.state` is active
- `ExpandedView` below
- transparent blocker over module content while recording

Add start/stop controls in a minimal place: the expanded header or recording bar. Start creates root directory:

```kotlin
File(context.getExternalFilesDir(null) ?: context.filesDir, "debugtools-recordings")
```

Stop calls `HtmlRecordingReportWriter().write(report, File(report.rootDir, "report.html"))` and renders saved path.

- [ ] **Step 4: Run core and app build**

Run: `./gradlew :debugtools-core:testDebugUnitTest :app:assembleDebug`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add debugtools-core/src/main/kotlin/com/debugtools/core \
  debugtools-conversation/src/main/kotlin/com/debugtools/conversation \
  debugtools-startup/src/main/kotlin/com/debugtools/startup \
  debugtools-okhttp-capture/src/main/kotlin/com/debugtools/okhttp \
  debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon \
  debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon \
  debugtools-stability/src/main/kotlin/com/debugtools/stability
git commit -m "feat(core): record modules from overlay"
```

---

## Task 8: Integration Documentation, Demo Update, and Final Verification

**Files:**
- Create: `docs/INTEGRATION.md`
- Modify: `CLAUDE.md`
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt` if the demo needs buttons/sample data for VoiceTrace or global recording.
- Modify: `app/src/main/kotlin/com/debugtools/sample/SampleApplication.kt` if startup or request trace sample events need early app lifecycle hooks.
- Modify: `AGENTS.md` only if it is intended to be tracked; otherwise leave it untracked.

**Interfaces:**
- Consumes: all implemented public APIs.
- Produces: current integration guide.

- [ ] **Step 1: Write integration doc**

Create `docs/INTEGRATION.md` with these sections:

```markdown
# DebugTools Integration

## Modules

- debugtools-core: overlay, module registry, settings, recording
- debugtools-network: network type, ping, quality
- debugtools-timeline: debug event timeline
- debugtools-general: disk and process checks
- debugtools-okhttp-capture: HTTP and WebSocket capture
- debugtools-perfmon: process CPU, RSS, thread sampling
- debugtools-audiomon: A/B audio recording and anomalies
- debugtools-startup: initialization steps and dependency analysis
- debugtools-conversation: requestId voice trace
- debugtools-stability: system crash, ANR, tombstone scan

## Minimal Setup

```kotlin
DebugTools.builder(context)
    .register(ConversationMonitorModule())
    .register(StartupMonitorModule())
    .build()
```

## Voice Trace

```kotlin
VoiceTrace.init(context, voiceTraceProfile {
    requestBoundary { exitEvents = listOf("RequestExit") }
    stage("ASR") {
        begin = "AsrBegin"
        end = "AsrEnd"
        includeInDuration = true
        showInConversation = true
        warnIfSlowMs = 800
    }
})

VoiceTrace.begin(requestId, "AsrBegin")
VoiceTrace.end(requestId, "AsrEnd")
VoiceTrace.instant(name = "RequestExit")
```

## Recording

Use the overlay recording bar to start and stop a recording. Reports are saved under app external files in `debugtools-recordings/<recordingId>/report.html`.

## Permissions

- `SYSTEM_ALERT_WINDOW`: required for overlay.
- `RECORD_AUDIO`: required for audiomon Stream B.
- system app access to DropBox and `/data/*`: required for stability sources.

## Mode Support

- ATTACHED: all modules supported.
- INDEPENDENT: no cross-process processed-audio stream for audiomon.
```

- [ ] **Step 2: Update CLAUDE.md module list**

Replace the old four-module architecture section with the current module list from `settings.gradle.kts`.

- [ ] **Step 3: Update sample demo if needed**

Review the sample app after implementation. If the requestId-first trace or recording UI is not demonstrable from existing controls, add a deterministic demo flow:

```kotlin
val requestId = "demo-${System.currentTimeMillis()}"
VoiceTrace.begin(requestId, "AsrBegin")
VoiceTrace.end(requestId, "AsrEnd")
VoiceTrace.instant(requestId, "NluBegin")
VoiceTrace.instant(requestId, "NluEnd")
VoiceTrace.instant(name = "RequestExit")
```

The demo must let a tester initialize DebugTools, generate at least one request trace, start and stop global recording, and find the saved `report.html`.

- [ ] **Step 4: Run local verification**

Run: `./gradlew test`

Expected: `BUILD SUCCESSFUL`.

Run: `./gradlew assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run emulator verification where feasible**

Confirm a device is connected:

```bash
adb devices
```

Expected: one emulator or device in `device` state.

Install and launch the sample app:

```bash
./gradlew :app:installDebug
adb shell monkey -p com.debugtools.sample 1
```

Verify manually or through logcat/screenshot where possible:

- DebugTools initializes without crashing.
- The overlay can enter expanded mode when overlay permission is already granted.
- The sample can generate a request trace and the conversation/request UI shows it.
- Starting recording freezes module interactions.
- Stopping recording saves `debugtools-recordings/<recordingId>/report.html`.
- Startup demo data shows initialization success/failure records.
- Stability module shows permission-limited state rather than blocking or crashing on a non-system emulator.

Collect evidence:

```bash
adb logcat -d | grep -E "DebugTools|VoiceTrace|Recording|Stability" | tail -80
```

If overlay permission is not granted and cannot be granted automatically, record that limitation and still verify install/build/logcat paths.

- [ ] **Step 6: Check git status**

Run: `git status --short`

Expected: only intended changes are present. If `AGENTS.md` and `REVIEW_WEEKLY.md` remain untracked and unrelated, do not stage them.

- [ ] **Step 7: Commit**

```bash
git add docs/INTEGRATION.md CLAUDE.md app/src/main/kotlin/com/debugtools/sample
git commit -m "docs: update debugtools integration guide"
```

---

## Self-Review Notes

- Spec coverage: protocol, configurable rules, requestId-first flow, exit fallback, global recording, startup details, HTML report, stability fixes, UI direction, and docs are each mapped to tasks.
- Type consistency: request profile and analyzer types are introduced in Tasks 1-2 before exporters and UI use them.
- Scope control: advanced report visualizations, cloud upload, PDF, multi-turn grouping, and independent-process audio streaming remain out of scope.
- Execution note: Task 7 is intentionally the largest because UI wiring and module exporters interact through the shared recording manager; use review checkpoints after each module exporter if implementation risk grows.
