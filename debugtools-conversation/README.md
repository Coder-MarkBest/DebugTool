# debugtools-conversation

`debugtools-conversation` shows a staged business link for one trace id. The default use case is a voice-assistant requestId, but the same protocol can map payment, navigation, media, car-control, page-load, or any other begin/end style business flow.

## Recommended Voice Assistant Setup

Use the built-in voice-assistant preset first. It gives the UI stable stage semantics for VAD, ASR, ASR arbitration, NLU, NLU arbitration, execution engine, TTS text, audio focus, cache read, synthesis, AudioTrack write, and TTS.

```kotlin
LinkTrace.init(
    context,
    VoiceAssistantTraceProfiles.standard(
        mapping = VoiceAssistantTraceMapping(exit = "dialogExit")
    )
)

LinkTrace.begin(requestId, "vadBegin")
LinkTrace.end(requestId, "vadEnd")
LinkTrace.begin(requestId, "AsrBegin")
LinkTrace.end(requestId, "AsrEnd", mapOf("text" to "打开空调"))
LinkTrace.begin(requestId, "NluBegin")
LinkTrace.end(requestId, "NluEnd", mapOf("intent" to "ac_on"))
LinkTrace.finish(requestId)
```

Default built-in rules:

| Stage id | Default begin | Default end | Label |
|----------|---------------|-------------|-------|
| `VAD` | `VadBegin` | `VadEnd` | VAD |
| `ASR` | `AsrBegin` | `AsrEnd` | ASR |
| `ASR_ARBITRATION` | `AsrArbitrationBegin` | `AsrArbitrationEnd` | ASR仲裁 |
| `NLU` | `NluBegin` | `NluEnd` | NLU |
| `NLU_ARBITRATION` | `NluArbitrationBegin` | `NluArbitrationEnd` | NLU仲裁 |
| `EXECUTION_ENGINE` | `ExecutionEngineBegin` | `ExecutionEngineEnd` | 执行引擎 |
| `TTS_TEXT_RECEIVED` | `TtsTextReceivedBegin` | `TtsTextReceivedEnd` | TTS收到文本 |
| `AUDIO_FOCUS` | `AudioFocusBegin` | `AudioFocusEnd` | 申请焦点 |
| `CACHE_READ` | `CacheReadBegin` | `CacheReadEnd` | 读取缓存 |
| `TTS_SYNTHESIS` | `SynthesisBegin` | `SynthesisEnd` | TTS合成 |
| `AUDIO_TRACK_WRITE` | `AudioTrackWriteBegin` | `AudioTrackWriteEnd` | 写入AudioTrack |
| `TTS` | `TtsBegin` | `TtsEnd` | TTS |

Register the visible module:

```kotlin
DebugTools.builder(context)
    .register(ConversationMonitorModule())
    .build()
```

## Custom Business Flow

For non-voice business, define a profile with neutral names. The only required concept is one id for one request.

```kotlin
val profile = linkTraceProfile {
    traceIdKey = "orderId"
    requestBoundary { exitEvents = listOf("OrderFinished") }
    stage("pay") {
        begin = "PayBegin"
        end = "PayEnd"
        label = "Payment"
        showInTimeline = true
        includeInDuration = true
        warnIfSlowMs = 1_000
        required = true
    }
}
LinkTrace.init(context, profile)
```

## Diagnostics

The analyzer reports:

- missing required stages,
- slow stages over `warnIfSlowMs`,
- error events,
- exit events without trace id when they must be matched to an active request.

Each rule is configured per stage or marker, so hosts can decide what appears in the timeline and what contributes to performance duration.

`VoiceTrace` remains as a compatibility wrapper. New code should use `LinkTrace`.
