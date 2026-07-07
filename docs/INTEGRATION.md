# DebugTools 接入文档

DebugTools 是面向 Android 语音助手/车机业务的调试 SDK。宿主 App 接入后，可以通过悬浮窗查看网络、对话链路、启动链路、性能、音频、稳定性和依赖可用性等信息，并可通过全局录制生成离线诊断报告。

本文面向业务接入方，按“最小接入 -> 按需模块 -> 业务埋点 -> 录制报告”的顺序说明。

## 1. 接入前准备

### 1.1 模块选择

必须接入：

- `debugtools-core`：悬浮窗、模块注册、设置存储、全局录制。

按需接入：

| 模块 | 用途 |
|---|---|
| `debugtools-network` | 网络状态、网关 ping，可融合 OkHttp 抓包 |
| `debugtools-okhttp-capture` | HTTP/WebSocket 抓包、耗时瀑布、请求详情 |
| `debugtools-conversation` | `traceId/requestId` 链路追踪，默认提供语音助手 profile |
| `debugtools-startup` | App 启动步骤、耗时、关键路径分析 |
| `debugtools-perfmon` | 进程 CPU/RSS/线程采样 |
| `debugtools-audiomon` | 麦克风输入监控、双流录音、异常检测 |
| `debugtools-stability` | 进程存活、DropBox/文件 crash 扫描 |
| `debugtools-general` | 可用性检查；公开入口是 `AvailabilityModule` |
| `debugtools-timeline` | 通用事件时间线工具 |

Sample App 里还有一个 `VoiceAssistantModule`，它不是必须接入的 SDK 功能模块，而是“设置项”示例模块，用来展示 `SingleSelect`、`MultiSelect`、`Toggle`、`EditText`、`Custom` 五类设置控件的写法。悬浮窗中显示为 `设置项` Tab。

### 1.2 权限

悬浮窗：

```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
```

音频监控：

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

注意：

- Android O 及以上使用 `TYPE_APPLICATION_OVERLAY`，必须先获得悬浮窗权限；否则初始化窗口时会抛 `OverlayPermissionException`。
- `debugtools-stability` 里的 DropBox、`/data/anr` 等来源偏系统应用场景。普通 App 上结果为空可能代表权限受限，不一定代表没有崩溃。
- 全局录制不会自动启动麦克风采集；需要用户在音频模块里手动开始音频监控。

## 2. 最小初始化

在 App 初始化或调试入口处注册模块：

```kotlin
DebugTools.builder(context)
    .processMode(ProcessMode.ATTACHED)
    .register(ConversationMonitorModule())
    .register(StartupMonitorModule())
    .register(PerfMonitorModule.builder().addProcessByName(context.packageName).build())
    .build()
```

`processMode` 支持：

| 模式 | 说明 |
|---|---|
| `ProcessMode.ATTACHED` | 默认模式，DebugTools 运行在主进程，悬浮窗直接管理 |
| `ProcessMode.INDEPENDENT` | DebugTools 运行在 `:debug` 进程，主进程通过 AIDL 通信 |

通常先使用 `ATTACHED`。如果调试工具本身需要和主进程隔离，再切到 `INDEPENDENT`。

如需自定义设置存储：

```kotlin
DebugTools.builder(context)
    .storage(DataStoreStorage(context, "debugtools_settings"))
    .register(ConversationMonitorModule())
    .build()
```

模块收到的 storage 已按 `moduleId/` 自动加前缀，业务模块不需要自己处理 key 冲突。

## 3. 推荐完整接入示例

下面示例包含网络、对话链路、启动链路、性能和可用性检查：

```kotlin
val capture = NetworkCaptureModule.create()

LinkTrace.init(
    applicationContext,
    VoiceAssistantTraceProfiles.standard(
        mapping = VoiceAssistantTraceMapping(
            startEvents = listOf("vadBegin"),
            exit = "dialogExit",
            vadBegin = "vadBegin",
            vadEnd = "vadEnd",
            executionEngineBegin = "ToolBegin",
            executionEngineEnd = "ToolEnd"
        ),
        extraMarkers = listOf(
            MarkerRule(
                name = "AsrPartial",
                label = "ASR 中间结果",
                showInConversation = true,
                includeInDuration = false,
                category = TraceCategory.ASR,
                order = 21
            ),
            MarkerRule(
                name = "CacheHit",
                label = "缓存命中",
                showInConversation = false,
                includeInDuration = false,
                category = TraceCategory.CUSTOM,
                order = 35
            )
        )
    )
)

DebugTools.builder(context)
    .processMode(ProcessMode.ATTACHED)
    .register(VoiceAssistantModule()) // Sample 设置项示例；正式业务可替换成自己的设置模块
    .register(
        NetworkModule.builder()
            .gateway("8.8.8.8")
            .capture(capture)
            .build()
    )
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
                        title = "隐私协议",
                        status = AvailabilityStatus.UNKNOWN,
                        message = "由宿主 App 提供状态"
                    )
                )
            }
            .build()
    )
    .build()
```

## 4. 设置项模块接入

如果宿主 App 希望在 DebugTools 里暴露调试开关、服务地址、日志级别等配置，可以实现一个普通 `DebugModule`，在 `buildSettings()` 里返回设置项。

Sample App 的 `VoiceAssistantModule` 就是这样的示例模块。它在悬浮窗里显示为 `设置项` Tab：

```kotlin
DebugTools.builder(context)
    .register(VoiceAssistantModule())
    .build()
```

设置项示例：

```kotlin
override fun buildSettings() = listOf(
    SettingGroup(
        title = "识别设置",
        items = listOf(
            SettingItem.SingleSelect(
                key = "log_level",
                label = "日志级别",
                options = listOf("Debug", "Info", "Warn", "Error"),
                default = "Debug",
                description = "选择 Debug 会输出所有日志，可能影响性能"
            ),
            SettingItem.Toggle(
                key = "asr_enabled",
                label = "启用 ASR",
                default = true
            ),
            SettingItem.EditText(
                key = "asr_server",
                label = "ASR 服务地址",
                default = "ws://asr.example.com:8080",
                hint = "ws:// 或 wss://"
            )
        )
    )
)
```

可用设置项：

| 类型 | 用途 |
|---|---|
| `SingleSelect` | 单选项，例如日志级别、环境选择 |
| `MultiSelect` | 多选项，例如音频处理能力开关 |
| `Toggle` | 布尔开关，例如启用 ASR |
| `EditText` | 文本输入，例如服务地址、实验参数 |
| `Custom` | 自定义 View，例如版本信息、复杂控制块 |

每个模块拿到的 `SettingsStorage` 已经按 `moduleId/` 自动隔离。设置项的 `key` 只需要保证在当前模块内唯一。

## 5. 网络抓包接入

推荐把 `NetworkCaptureModule` 托管到 `NetworkModule`，这样网络质量和抓包共用一个 Tab：

```kotlin
val capture = NetworkCaptureModule.create()

DebugTools.builder(context)
    .register(
        NetworkModule.builder()
            .gateway("8.8.8.8")
            .capture(capture)
            .build()
    )
    .build()
```

OkHttp 使用同一个 `capture` 对象：

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(capture.httpInterceptor())
    .eventListenerFactory(capture.eventListenerFactory())
    .build()
```

注意：

- 不要在 `NetworkModule` 已托管 capture 时再单独注册 `NetworkCaptureModule`，否则会出现两个网络入口。
- 抓包耗时通过每次 call 的记录 id 关联，不依赖 URL 字符串，因此可以区分同 URL 并发请求。
- WebSocket 需要使用 capture 提供的 listener 包装入口，具体以 `NetworkCaptureModule` 暴露的 API 为准。

## 6. 对话链路 Trace 接入

### 5.1 核心概念

`debugtools-conversation` 使用 `LinkTrace` 记录业务链路。一个 `traceId` 表示一次业务请求：

- 语音助手通常使用 `requestId`
- 导航可以使用 `routeId`
- 支付可以使用 `orderId`
- 媒体播放可以使用 `playRequestId`

第一版不做多轮会话聚合，先保证一次请求内的链路可诊断。

### 5.2 使用默认语音助手 profile

默认 profile：

```kotlin
LinkTrace.init(
    applicationContext,
    VoiceAssistantTraceProfiles.standard()
)
```

默认 `requestKey = "requestId"`。

默认边界：

| 类型 | 默认事件 |
|---|---|
| startEvents | `VadBegin` |
| exitEvents | `DialogExit` |
| fallbackTimeoutMs | `30000ms` |

默认 stage：

| Stage | Begin | End | 必需 | 慢阈值 |
|---|---|---|---|---|
| VAD | `VadBegin` | `VadEnd` | 是 | 300ms |
| ASR | `AsrBegin` | `AsrEnd` | 是 | 800ms |
| ASR仲裁 | `AsrArbitrationBegin` | `AsrArbitrationEnd` | 否 | 300ms |
| NLU | `NluBegin` | `NluEnd` | 是 | 500ms |
| NLU仲裁 | `NluArbitrationBegin` | `NluArbitrationEnd` | 否 | 300ms |
| 执行引擎 | `ExecutionEngineBegin` | `ExecutionEngineEnd` | 否 | 1500ms |
| TTS收到文本 | `TtsTextReceivedBegin` | `TtsTextReceivedEnd` | 否 | 200ms |
| 申请焦点 | `AudioFocusBegin` | `AudioFocusEnd` | 否 | 300ms |
| 读取缓存 | `CacheReadBegin` | `CacheReadEnd` | 否 | 200ms |
| TTS合成 | `SynthesisBegin` | `SynthesisEnd` | 否 | 1000ms |
| 写入AudioTrack | `AudioTrackWriteBegin` | `AudioTrackWriteEnd` | 否 | 300ms |
| TTS | `TtsBegin` | `TtsEnd` | 否 | 1000ms |

这些 stage 默认都展示在 UI，并参与性能耗时统计。

### 5.3 适配宿主 App 的事件名

如果业务已有事件名，不需要为了 DebugTools 改埋点名，使用 `VoiceAssistantTraceMapping` 映射即可：

```kotlin
LinkTrace.init(
    applicationContext,
    VoiceAssistantTraceProfiles.standard(
        mapping = VoiceAssistantTraceMapping(
            startEvents = listOf("vadBegin"),
            exit = "dialogExit",
            vadBegin = "vadBegin",
            vadEnd = "vadEnd",
            executionEngineBegin = "ToolBegin",
            executionEngineEnd = "ToolEnd"
        )
    )
)
```

### 5.4 记录事件

```kotlin
val requestId = "req-001"

LinkTrace.begin(requestId, "vadBegin", mapOf("source" to "wake_word"))
LinkTrace.end(requestId, "vadEnd")

LinkTrace.begin(requestId, "AsrBegin")
LinkTrace.instant(requestId, "AsrPartial", mapOf("text" to "打开空调"))
LinkTrace.end(requestId, "AsrEnd", mapOf("text" to "打开空调"))

LinkTrace.begin(requestId, "NluBegin")
LinkTrace.end(requestId, "NluEnd", mapOf("intent" to "ac_on"))

LinkTrace.finish(requestId, TraceOutcome.SUCCESS)
```

也可以手动构造事件：

```kotlin
LinkTrace.mark(
    LinkTraceEvent(
        traceId = requestId,
        name = "NluError",
        type = TraceEventType.ERROR,
        timestampUptimeMs = SystemClock.uptimeMillis(),
        attributes = mapOf("reason" to "IntentNotFound")
    )
)
```

### 5.5 UI 展示和 raw events 规则

对话链路模块会保留完整原始事件：

- `rawEvents` 保存所有事件，包括重复事件、隐藏 marker、错误事件。
- UI 链路图按事件名只展示第一次出现的事件；重复出现的同名事件不重复展示。
- stage 耗时使用第一次可计算的 begin/end。
- `includeInDuration = false` 的 marker 可以展示，但不计入性能耗时。
- `showInConversation = false` 的 marker 不展示，但仍保留在 raw events 和录制产物里。

### 5.6 非语音业务链路

也可以用业务中立 DSL：

```kotlin
val paymentProfile = linkTraceProfile {
    traceIdKey = "orderId"
    requestBoundary { exitEvents = listOf("OrderFinished") }
    stage("pay") {
        begin = "PayBegin"
        end = "PayEnd"
        label = "支付"
        showInTimeline = true
        includeInDuration = true
        warnIfSlowMs = 1_000
        required = true
    }
}

LinkTrace.init(applicationContext, paymentProfile)
LinkTrace.begin(orderId, "PayBegin")
LinkTrace.end(orderId, "PayEnd")
LinkTrace.finish(orderId)
```

`VoiceTrace` 仍作为兼容层保留，新接入建议使用 `LinkTrace`。

## 7. 启动链路接入

注册模块：

```kotlin
DebugTools.builder(context)
    .register(StartupMonitorModule())
    .build()
```

业务侧通过启动 recorder 记录步骤，核心语义是：

- 每个启动会话有唯一 session id。
- 每个 step 有开始/结束时间、状态和依赖关系。
- `dependsOn` 用于计算关键路径。
- 最近会话会持久化，悬浮窗重新打开后仍可查看。

建议接入方式：

- 在 `Application`、首屏 Activity、核心 SDK 初始化处记录启动步骤。
- 使用单调时间记录耗时。
- 对失败步骤填充明确 error message。
- 对可并行步骤填写依赖关系，不要只记录线性列表。

## 8. 应用初始化流程编排

如果业务希望用一套声明式流程管理应用初始化，可以接入 `startup-init-flow`。它不依赖 DebugTools；即使正式包不包含 DebugTools，也能正常执行初始化任务。

独立使用：

```kotlin
StartupInitFlow.builder()
    .task("config") { runBlockingInit { initConfig() } }
    .task("asr", dependsOn = listOf("config")) { suspendInit { initAsr() } }
    .task("tts", dependsOn = listOf("asr")) { callbackInit { done -> initTts { done(it) } } }
    .run()
```

接入 DebugTools 启动链路：

```kotlin
AppStartupMonitor.init(this, appVersion = BuildConfig.VERSION_NAME)

StartupInitFlow.builder()
    .task("config") { runBlockingInit { initConfig() } }
    .task("asr", dependsOn = listOf("config")) { suspendInit { initAsr() } }
    .reportToStartupMonitor()
    .run()
```

依赖语义：

- 依赖全部成功后，任务才会执行。
- 无依赖或依赖已满足的任务会并发执行。
- 某个任务失败后，依赖它的后续任务会标记为 `SKIPPED`。
- 与失败分支无关的任务继续执行。
- 接入 `debugtools-startup-init` 后，任务结果会进入“启动链路”Tab 和全局录制报告。

## 9. 性能监控接入

注册当前 App 进程：

```kotlin
DebugTools.builder(context)
    .register(
        PerfMonitorModule.builder()
            .addProcessByName(context.packageName)
            .build()
    )
    .build()
```

说明：

- demo/普通 App 环境下，`/proc/stat` 可能因 SELinux 受限不可读；模块会使用可用信息做降级。
- 目标如果是系统应用，实际可读范围可能和 sample app 不同。
- 采样包含进程 CPU/RSS 和线程信息，适合定位语音请求期间的 CPU 抖动、线程忙等问题。

## 10. 可用性检查接入

`AvailabilityModule` 用来表达“业务依赖是否可用”，例如隐私协议、麦克风开关、账号状态、NLU/TTS 服务、车控服务等。

```kotlin
DebugTools.builder(context)
    .register(
        AvailabilityModule.builder(context)
            .addProcessCheck(listOf(context.packageName, "com.vendor.tts"))
            .addNetworkCheck()
            .addExternalSource {
                listOf(
                    AvailabilityItem(
                        id = "mic_permission",
                        title = "麦克风权限",
                        status = if (hasMicPermission()) {
                            AvailabilityStatus.AVAILABLE
                        } else {
                            AvailabilityStatus.UNAVAILABLE
                        },
                        message = "宿主 App 提供权限状态"
                    )
                )
            }
            .build()
    )
    .build()
```

状态枚举：

| 状态 | 含义 |
|---|---|
| `AVAILABLE` | 可用 |
| `DEGRADED` | 可用但降级 |
| `UNAVAILABLE` | 不可用 |
| `UNKNOWN` | 未知或宿主暂未提供 |

总览页会优先展示不可用、降级、未知项。

## 11. 音频监控接入

注册模块：

```kotlin
DebugTools.builder(context)
    .register(AudioMonitorModule())
    .build()
```

使用建议：

- 先申请 `RECORD_AUDIO`。
- 在音频 Tab 手动开始/停止采集。
- 录制文件、数值特征和异常结果会落到本地目录，便于车机离线分析。
- 如果 emulator 看起来没有输入，先确认模拟器麦克风是否真的产生非零 PCM。

## 12. 全局录制和报告

悬浮窗展开态顶部会显示全局录制条：

1. 点击 `开始录制` 创建录制 session。
2. 录制期间模块 UI 会被遮罩，避免误操作；各模块采样、Trace 收集继续进行。
3. 点击 `停止录制`，各 `RecordableModule` 导出产物，并生成 `report.html`。

默认保存路径：

```text
<external-files-dir>/debugtools-recordings/<yyyyMMdd_HHmmss_suffix>/
```

典型文件：

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

说明：

- `report.html` 可离线打开，汇总模块概览、诊断问题和原始产物位置。
- 原始 JSON 会保留在报告旁边，用于深度分析或宿主 App 自行上传。
- 如果 OkHttp capture 被 `NetworkModule` 托管，报告中按 `debugtools_network` 汇总，原始抓包 JSON 仍保留在 capture 目录。

## 13. Sample App 验证流程

推荐用 sample app 先验证接入效果：

1. 启动 sample app。
2. 授权悬浮窗权限。
3. 点击初始化 DebugTools。
4. 打开悬浮窗。
5. 点击 `生成示例对话链路（4 个 requestId）`。
6. 查看 `对话链路` Tab。
7. 点击 `生成示例启动会话` 和 `生成示例网络流量`。
8. 开始全局录制，再生成一轮示例数据。
9. 停止录制，打开 toast 中提示的 `report.html` 路径。

示例对话链路包含：

- `demo-request-full`：覆盖当前 sample profile 的全部 stage。
- `demo-request-1`：成功请求，包含展示 marker 和隐藏 marker。
- `demo-request-2`：NLU 失败，包含 error event 和缺失必需 end。
- `demo-request-3`：exit event 不带 requestId，用于验证 active-request fallback。

## 14. 常见问题

### 悬浮窗打不开

检查：

- 是否申请并授予 `SYSTEM_ALERT_WINDOW`。
- 是否运行在 Android O 以上且使用 overlay 类型。
- 是否捕获到 `OverlayPermissionException`。

### 对话链路没有数据

检查：

- 是否先调用 `LinkTrace.init(...)`，再注册/打开 `ConversationMonitorModule`。
- 业务埋点的事件名是否和 profile mapping 一致。
- `traceId/requestId` 是否为空。
- 是否调用了 `LinkTrace.begin/end/instant/mark`。

### 同名事件多次出现，UI 只显示一次

这是当前设计：UI 按事件名展示第一次出现，避免重复事件撑爆链路图。完整事件仍在 `rawEvents` 和录制产物中保留。

### 报告没有音频文件

全局录制不自动启动麦克风。需要进入音频模块手动开始音频监控，且已授予 `RECORD_AUDIO`。

### 普通 App 上稳定性数据为空

可能是权限限制。DropBox、`/data/anr` 等来源更适合系统应用或有特权的环境。

### Network tab 里没有抓包

检查：

- `NetworkModule.builder().capture(capture).build()` 是否使用了同一个 `capture`。
- OkHttpClient 是否添加了 `capture.httpInterceptor()`。
- 是否添加了 `capture.eventListenerFactory()`。

## 15. 接入建议

- 先只接 `debugtools-core + debugtools-conversation + debugtools-network`，确认悬浮窗和核心链路可见。
- 再接启动、性能、音频、稳定性等模块。
- Trace 事件名优先复用业务已有埋点，通过 profile mapping 做适配。
- 车机环境优先使用本地文件和宿主 App 自有上传链路，不依赖系统分享。
- 每次新增模块后，用 sample app 或真实宿主跑一轮全局录制，确认 `report.html` 和 raw JSON 都可用。
