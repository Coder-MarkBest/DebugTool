# RequestId Voice Trace, Global Recording, and HTML Report Design

## Purpose

DebugTools needs a voice-assistant trace protocol that stays isolated from business-specific event names while still matching real voice-assistant workflows. The host app may emit markers such as `vadEnd`, `AsrBegin`, `AsrEnd`, `NluBegin`, and `NluEnd`; DebugTools must not hard-code those meanings. Instead, the SDK records raw facts, applies a configurable profile, displays a request timeline, calculates performance, and includes the result in a global recording report.

The first implementation is requestId-first: one `requestId` represents one voice request. Multi-turn conversation grouping is explicitly out of scope for this version.

## Goals

- Accept business trace events without understanding business-specific marker names.
- Support configurable display and performance rules per stage or marker.
- Use `requestId` as the primary aggregation key for one voice request.
- Support exit events that may not carry `requestId`.
- Add global recording across modules with UI interaction frozen until the user stops recording.
- Save module data and a local single-file HTML analysis report.
- Fix only the issues that affect correctness, integration reliability, recording, or the required UI polish.

## Non-Goals

- Multi-turn conversation aggregation.
- Cloud upload of reports.
- PDF report generation.
- A full Material Design rewrite.
- Complex external charting libraries.
- Independent-process audio stream recording.

## Voice Trace Protocol

### Public Entry Point

Add a requestId-first trace API in `debugtools-conversation`:

```kotlin
object VoiceTrace {
    fun init(context: Context, profile: VoiceTraceProfile)
    fun mark(event: VoiceTraceEvent)

    fun begin(requestId: String, name: String, attrs: Map<String, String> = emptyMap())
    fun end(requestId: String, name: String, attrs: Map<String, String> = emptyMap())
    fun instant(requestId: String? = null, name: String, attrs: Map<String, String> = emptyMap())

    // Convenience method only. Exit events are also valid request-ending signals.
    fun finish(requestId: String? = null, outcome: TraceOutcome = TraceOutcome.SUCCESS)
}
```

`finish(requestId)` is not the only close path because the host may not always have a `requestId` at exit time. The recommended close signal is a configured exit event.

### Event Model

```kotlin
data class VoiceTraceEvent(
    val requestId: String?,
    val name: String,
    val type: TraceEventType,
    val timestampUptimeMs: Long,
    val wallTimeMs: Long = System.currentTimeMillis(),
    val attributes: Map<String, String> = emptyMap()
)

enum class TraceEventType {
    BEGIN,
    END,
    INSTANT,
    ERROR
}
```

All raw events are preserved, including events that are not displayed or included in duration calculations.

### Configurable Profile

Support both code and file configuration. The first implementation includes the Kotlin DSL plus a JSON profile loader. A `VoiceTraceProfileLoader` interface is kept open so YAML can be added later without changing the trace API or analyzer.

```kotlin
VoiceTraceProfile {
    requestKey = "requestId"

    requestBoundary {
        startEvents = listOf("vadBegin", "AsrBegin")
        exitEvents = listOf("DialogExit", "RequestExit", "TtsEnd")
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

    marker("vadEnd") {
        label = "VAD End"
        showInConversation = true
        includeInDuration = false
        order = 10
    }

    marker("internalCacheHit") {
        showInConversation = false
        includeInDuration = false
    }
}
```

Each stage or marker supports:

- `showInConversation`: whether to show it in the request detail UI.
- `includeInDuration`: whether it participates in duration, slow-stage, and performance analysis.
- `label`: UI label independent of business marker names.
- `category`: VAD, ASR, NLU, DM, TTS, Tool, Network, Custom.
- `warnIfSlowMs`: stage-specific slow threshold.
- `required`: whether missing evidence is a critical issue.
- `order`: display order when timeline semantics matter more than raw arrival order.

The JSON profile uses the same fields as the DSL. Hosts that prefer YAML can convert YAML to the same JSON shape or provide a custom `VoiceTraceProfileLoader`.

### Request Closure Rules

When an exit event arrives:

- If it carries `requestId`, close that request.
- If it does not carry `requestId` and one active request exists, close that request.
- If multiple active requests exist, close the most recently active request and add a warning: exit event missing requestId, matched by latest active request.
- If no active request exists, record an orphan exit event and add a warning.
- If an active request exceeds `fallbackTimeoutMs`, auto-finalize it and add a timeout warning.

This keeps the protocol aligned with host reality while preserving uncertainty in the report.

### Analysis Output

For each request:

- `rawEvents`: all events received for the request.
- `timelineItems`: configured stages and markers to show in UI.
- `performance`: only stages with `includeInDuration = true`.
- `issues`: required stage missing, unpaired begin/end, slow stage, error event, order anomaly, orphan exit, fallback timeout.

The current `ConversationTracer.submitTurn()` remains for compatibility, but the new UI should use request terminology: request list -> request detail -> raw events.

## Global Recording

### Core Interfaces

Add recording support in `debugtools-core`:

```kotlin
interface RecordableModule {
    val recorderId: String

    fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot
    fun onRecordingStop(context: RecordingContext): ModuleRecordingResult
}

data class RecordingContext(
    val recordingId: String,
    val startedAtWallMs: Long,
    val startedAtUptimeMs: Long,
    val rootDir: File
)

data class ModuleRecordingResult(
    val moduleId: String,
    val files: List<File>,
    val issues: List<RecordingIssue>,
    val summary: Map<String, String>
)
```

`DebugRecordingManager` owns the global state:

```kotlin
object DebugRecordingManager {
    fun start(modules: List<DebugModule>): RecordingContext
    fun stop(): RecordingSessionReport
    val state: StateFlow<RecordingState>
}
```

Only one recording may be active at a time.

### UI Behavior

When recording starts:

- A global recording bar appears at the top of the expanded panel.
- It shows elapsed time and a single stop action.
- All module content is visually frozen with a lightweight overlay.
- Users may look at data but cannot click module controls, clear data, refresh, upload, or change filters.
- Module internals continue collecting events and samples.

When recording stops:

- All recordable modules export their data.
- Failures in one module do not prevent the session from closing.
- The UI shows the saved report path.

### Directory Layout

```text
debugtools-recordings/
  20260704_153012_x7k2/
    manifest.json
    report.html
    conversation/
      raw-events.json
      requests.json
    startup/
      sessions.json
    network/
      http.json
      websocket.json
    perfmon/
      samples.json
    audiomon/
      session.json
      streamA.wav
      streamB.wav
    stability/
      crashes.json
```

### Module Recording Scope

- `conversation`: raw `VoiceTraceEvent`s, analyzed request timelines, request issues.
- `startup`: latest/current startup sessions, all step statuses, dependencies, errors, slow/missing-end diagnostics.
- `okhttp-capture`: current HTTP/WebSocket repository snapshot.
- `perfmon`: samples collected during the recording window.
- `audiomon`: controlled audio session if available; export WAV, features, session metadata, anomalies.
- `stability`: start and stop process status plus new crash/ANR/native entries.
- `network`, `timeline`, `general`: snapshot export first; full event recording can be added if the implementation remains small and testable.

Unsupported modules are listed as not recordable.

## Startup Recording Details

Startup is not just total launch duration. The report must preserve every initialization step:

- step name
- status: success, failed, running, skipped
- start offset
- end offset
- duration
- dependencies
- error message

Startup report issues include:

- initialization failed
- slow step
- missing end
- dependency starts before dependency completes
- dependency cycle
- could be parallelized but ran serially

If startup already completed before global recording starts, export the latest persisted/current session. If startup is still in flight, export a start snapshot and a stop snapshot.

## HTML Report

`report.html` is a local single-file report with embedded CSS/JS and links to adjacent raw artifacts. It must work offline.

### Sections

1. Overview
   - recording ID, start/end time, duration, saved path
   - issue counts by severity
   - module export status

2. Voice Requests
   - requestId list
   - total span from first raw event to last raw event
   - performance duration from configured stages
   - timeline stages and markers
   - issues and raw event expansion

3. Startup
   - total duration
   - success/failed/running counts
   - initialization step table
   - Gantt visualization
   - dependency and failure diagnostics

4. Network
   - HTTP count, failure count, slow requests, 4xx/5xx
   - WebSocket sessions, frames, abnormal close

5. Performance
   - max/avg CPU, max RSS, max threads
   - threshold issues

6. Audio
   - A/B stream availability
   - duration, RMS, peak, silence ratio
   - anomaly list
   - relative links to WAV/features files

7. Stability
   - process status at start/stop
   - new crash/ANR/native entries
   - explicit permission-limited state when system sources are unreadable

### Issue Severity

Critical:

- startup initialization failed
- required voice stage missing
- voice ERROR event
- target process crash or death

Warning:

- configured slow voice stage
- startup slow step, missing end, dependency issue
- HTTP 4xx/5xx or timeout
- CPU/RSS threshold exceeded
- audio clipping or abnormal silence
- exit event matched without requestId

Info:

- marker shown but not counted in duration
- module export failure that does not invalidate recording
- permission-limited module data

## Required Fixes

Fixes are limited to issues that affect correctness, integration, recording, report quality, or required UI polish.

1. Stability parsing
   - Java crash: parse `Process: com.xxx`.
   - ANR: parse `Cmd line: com.xxx`.
   - Native tombstone: parse `>>> com.xxx <<<`.
   - Add tests for each format.

2. Stability threading
   - Move DropBox and `/data/*` scans to IO.
   - UI displays loading, success, permission-limited, and failure states.

3. Conversation close semantics
   - Do not require explicit `requestId` for closure.
   - Support configured exit events and active-request fallback.
   - Keep old APIs compatible but remove misleading behavior where possible.

4. Documentation
   - Add or update integration documentation with current modules, permissions, requestId protocol, recording workflow, and mode support.

## UI Direction

The UI should become a restrained diagnostic console for voice debugging, not a decorative redesign.

Design choices:

- One global recording bar, always visible in expanded mode while recording.
- Compact module layouts optimized for scan speed.
- Consistent status colors: critical red, warning amber, success green, neutral blue-gray.
- Clear empty, loading, permission-limited, and error states.
- Conversation UI renamed around requestId: request list, request timeline, raw events.
- Startup UI emphasizes module initialization success/failure in a table plus Gantt.
- HTML report uses the same language and severity system as the in-app UI.

The first visual pass should avoid a full Material rewrite. It should unify spacing, labels, states, and severity treatment across the modules touched by recording.

## Test Strategy

Use TDD for behavior changes.

Required tests:

- Voice profile DSL builds stage and marker rules.
- Voice analyzer pairs begin/end by requestId.
- Marker can show without duration.
- Hidden marker is saved in raw events but absent from timeline.
- Exit event with requestId closes that request.
- Exit event without requestId closes the only active request.
- Exit event without requestId among multiple active requests creates a warning.
- Missing required stage is critical.
- Slow configured stage is warning.
- Startup export includes step status, dependencies, and errors.
- Stability parser recognizes `Process:`, `Cmd line:`, and tombstone `>>>`.
- HTML writer includes sections and issue severities.
- Recording manager rejects nested recordings and survives per-module export failure.

Verification:

- Run targeted module tests during implementation.
- Run `./gradlew test` before completion.
- If UI is changed substantially, validate in the sample app on emulator/device where available.

## Implementation Order

1. Voice trace protocol, profile DSL, recorder, analyzer, and tests.
2. Request-first conversation UI and compatibility with existing `ConversationTracer`.
3. Core recording contracts and `DebugRecordingManager`.
4. Module recording exporters.
5. HTML report writer.
6. Stability parser/threading fixes.
7. UI recording bar, frozen module overlay, and state polish.
8. Integration documentation.
