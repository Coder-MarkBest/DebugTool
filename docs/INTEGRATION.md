# DebugTools Integration Guide

DebugTools is an Android voice-assistant debug SDK shown as an overlay. The core module owns the floating window, settings, process mode, and global recording workflow. Feature modules are optional and can be registered independently.

## Modules

- `debugtools-core`: required overlay, settings, storage, process mode, and global recording.
- `debugtools-conversation`: requestId-first voice trace timeline and recording artifacts.
- `debugtools-startup`: app startup steps, success/failure state, and startup recording export.
- `debugtools-okhttp-capture`: OkHttp HTTP/WebSocket capture and summary export.
- `debugtools-perfmon`: process CPU/RSS/thread time series and export.
- `debugtools-audiomon`: explicit audio monitor; global recording exports current audio state only.
- `debugtools-stability`: process alive status, DropBox/file crash scan, and export.
- `debugtools-network`, `debugtools-timeline`: network quality and event timeline utilities.
- `debugtools-general`: availability checks. The public entry point is `AvailabilityModule`; built-in checks cover process liveness and network availability, and host apps can provide abstract external availability items.

## Permissions

- Overlay requires `SYSTEM_ALERT_WINDOW` on Android O and above. `FloatingWindowManager.init()` throws `OverlayPermissionException` if the permission is missing.
- Audio monitor requires `RECORD_AUDIO`. Global recording does not auto-start microphone capture; users must start audio monitoring explicitly in the audio tab.
- Stability sources such as DropBox and `/data/anr` are system-app oriented. On normal apps, empty crash results can mean permission-limited sources rather than no crashes.

## Basic Setup

```kotlin
val capture = NetworkCaptureModule.create()

VoiceTrace.init(applicationContext, voiceTraceProfile {
    requestKey = "requestId"
    requestBoundary {
        startEvents = listOf("vadBegin")
        exitEvents = listOf("dialogExit")
        fallbackTimeoutMs = 30_000
    }
    stage("ASR") {
        begin = "AsrBegin"
        end = "AsrEnd"
        label = "ASR"
        category = TraceCategory.ASR
        showInConversation = true
        includeInDuration = true
        warnIfSlowMs = 450
        required = true
        order = 20
    }
    marker("AsrPartial") {
        label = "ASR partial"
        showInConversation = true
        includeInDuration = false
        category = TraceCategory.ASR
    }
})

DebugTools.builder(context)
    .processMode(ProcessMode.ATTACHED)
    .register(capture)
    .register(ConversationMonitorModule())
    .register(StartupMonitorModule())
    .register(PerfMonitorModule.builder().addProcessByName(context.packageName).build())
    .register(
        AvailabilityModule.builder(context)
            .addProcessCheck(listOf(context.packageName))
            .addNetworkCheck()
            .addExternalSource {
                listOf(
                    AvailabilityItem(
                        id = "privacy",
                        title = "Privacy agreement",
                        status = AvailabilityStatus.UNKNOWN,
                        message = "Host app supplies this state"
                    )
                )
            }
            .build()
    )
    .build()
```

## Availability Protocol

`AvailabilityModule` is intentionally business-agnostic. It does not know whether an item represents an NLU engine, TTS volume, microphone readiness, privacy consent, user settings, or any other product-specific dependency. Host apps map those conditions to `AvailabilityItem`:

```kotlin
AvailabilityItem(
    id = "dependency_id",
    title = "Dependency name",
    status = AvailabilityStatus.UNAVAILABLE,
    message = "Reason shown in the debug panel"
)
```

Statuses are `AVAILABLE`, `DEGRADED`, `UNAVAILABLE`, and `UNKNOWN`. The overview tab prioritizes unavailable items first, then degraded, then unknown.

## Voice Trace Protocol

One `requestId` represents one assistant request. Multi-turn grouping is intentionally out of scope for the first version.

Business code reports raw events and DebugTools interprets them through a profile. This keeps business marker names such as `vadEnd`, `AsrBegin`, `NluEnd`, and `dialogExit` outside the UI/report logic.

```kotlin
VoiceTrace.begin(requestId, "vadBegin")
VoiceTrace.end(requestId, "vadEnd")
VoiceTrace.begin(requestId, "AsrBegin")
VoiceTrace.instant(requestId, "AsrPartial", mapOf("text" to "hello"))
VoiceTrace.end(requestId, "AsrEnd", mapOf("text" to "hello world"))
VoiceTrace.begin(requestId, "NluBegin")
VoiceTrace.end(requestId, "NluEnd", mapOf("intent" to "greeting"))
VoiceTrace.finish(requestId, TraceOutcome.SUCCESS)
```

Configuration controls three independent questions:

- Whether an event/stage is displayed in conversation UI: `showInConversation`.
- Whether it contributes to performance duration: `includeInDuration`.
- Whether missing/slow data should be reported: `required`, `warnIfSlowMs`.

Exit events can omit `requestId`. If exactly one request is active, the recorder closes it and adds an issue explaining the fallback. If multiple requests are active, it closes the latest active request and records the ambiguity.

## Global Recording

The expanded floating window shows a global recording bar.

- Tap `开始录制` to create a recording session under app external files.
- During recording, module content is covered by an interaction blocker; only the recording bar remains usable.
- Module sampling and trace collection continue while interaction is locked.
- Tap `停止录制` to export module artifacts and generate `report.html`.

Default save root:

```text
<external-files-dir>/debugtools-recordings/<yyyyMMdd_HHmmss_suffix>/
```

Typical files:

```text
report.html
conversation/raw-events.json
conversation/requests.json
startup/sessions.json
debugtools_okhttp_capture/network-summary.json
debugtools_perfmon/perf-series.json
audiomon/audio-state.json
stability/stability.json
```

`report.html` is offline and summarizes module artifacts and issues. Raw JSON files are preserved next to it for deeper analysis.

## Demo App

The sample app initializes `VoiceTrace` before registering `ConversationMonitorModule()`. Use `生成示例对话链路（3 个 requestId）` to generate:

- a successful request with displayed and hidden markers,
- an NLU failure with an error event and missing required end,
- an exit event without `requestId` to exercise active-request fallback.

After initializing DebugTools, open the overlay, start global recording, generate sample startup/conversation/network data, then stop recording. The app shows a toast with the generated `report.html` path.
