# OkHttp 网络抓包模块设计文档

> DebugTools SDK 的新增模块 `debugtools-okhttp-capture`，提供 HTTP + WebSocket 流量记录与查看能力。

---

## 1. 概述

为 DebugTools SDK 增加一个新的可选模块 `debugtools-okhttp-capture`，让接入方在车机端能直接看到自己应用与服务端之间的 HTTP / WebSocket 通信过程。**只读浏览**，不支持改包/Mock。

**典型使用场景：**
- 端云请求失败/慢，需要看请求参数和响应内容
- WebSocket 业务消息流的时序排查
- 验证大模型链路的流式回包格式

---

## 2. 范围与非范围

### V1 范围
| 能力 | 说明 |
|---|---|
| HTTP/HTTPS 抓包 | 通过 OkHttp Interceptor 拦截，包括 WebSocket 握手 |
| WebSocket 帧抓包 | 通过 `newWebSocket` 工厂包装，记录所有发送/接收帧 |
| 详情查看 | HTTP 4 Tab、WS 4 Tab，含 JSON 美化、Headers 折叠、时序 waterfall |
| 列表展示 | 混合 + 折叠视图，时间正序追加，自动滚动到底，拖动时暂停跟随 |
| 数据治理 | 默认上限保护，超出 LRU 驱逐；大 body/frame 截断 |

### V1 非范围（后续版本）
- Mock / 改包 / 请求重发
- 字节码插桩（V1 走业务方主动接入 API）
- 反射 hook OkHttpClient（V1 不做）
- 关键字搜索、复杂过滤
- 持久化到磁盘（V1 仅内存）
- HTTPS 之外协议（gRPC、原生 socket、Ktor 等）
- 三方 SDK 黑盒 native 网络层（如科大讯飞 MSC SDK）

---

## 3. 库拆分

```
debugtools-okhttp-capture/          ← 新增模块
  ├── build.gradle.kts              ← 依赖 :debugtools-core + OkHttp 4.x
  └── src/main/kotlin/com/debugtools/okhttp/
      ├── NetworkCaptureModule.kt   ← 实现 DebugModule，对外入口
      ├── api/                      ← 公开 API
      │   ├── HttpInterceptor.kt
      │   └── WebSocketFactory.kt
      ├── capture/                  ← 拦截内部实现
      │   ├── CapturingInterceptor.kt
      │   ├── CapturingWebSocket.kt
      │   ├── CapturingListener.kt
      │   └── EventListener.kt      ← OkHttp EventListener，采集时序数据
      ├── data/                     ← 数据模型
      │   ├── HttpRecord.kt
      │   ├── WebSocketSession.kt
      │   ├── WebSocketFrame.kt
      │   └── Timing.kt
      ├── repository/               ← 内存存储
      │   └── NetworkRepository.kt
      ├── presenter/
      │   └── NetworkCapturePresenter.kt
      └── view/
          ├── NetworkCaptureView.kt
          ├── adapter/              ← RecyclerView 适配器
          │   ├── ListAdapter.kt
          │   └── DetailFragment.kt（或独立 View）
          └── widget/
              ├── HeaderFoldView.kt
              ├── JsonPrettyView.kt
              └── TimingWaterfallView.kt
```

接入方依赖：

```kotlin
// app/build.gradle.kts
debugImplementation("io.github.xxx:debugtools-okhttp-capture:1.0")
```

模块自身依赖：

```kotlin
// debugtools-okhttp-capture/build.gradle.kts
dependencies {
    implementation(project(":debugtools-core"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
```

---

## 4. 公开 API

### NetworkCaptureModule 入口

```kotlin
class NetworkCaptureModule private constructor(
    private val config: Config
) : DebugModule {

    override val moduleId = "debugtools_okhttp_capture"
    override val tabTitle = "网络抓包"

    /** HTTP 拦截器，加入 OkHttpClient.Builder.addInterceptor() 即可。 */
    fun httpInterceptor(): Interceptor = CapturingInterceptor(repository)

    /**
     * OkHttp EventListener 工厂，提供 HTTP 阶段时序数据（DNS / Connect / TLS / TTFB 等）。
     * 接入到 OkHttpClient.Builder.eventListenerFactory() 后，详情视图的时序 Tab 可以
     * 展示 waterfall 图；不加则时序 Tab 只显示总耗时。
     */
    fun eventListenerFactory(): EventListener.Factory = TimingEventListener.Factory(repository)

    /**
     * 替代 OkHttpClient.newWebSocket(...) 的工厂函数。
     * 返回值是标准 okhttp3.WebSocket，业务后续 send/close 用法不变。
     */
    fun newWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener
    ): WebSocket {
        val sessionId = UUID.randomUUID().toString()
        val capturingListener = CapturingListener(listener, repository, sessionId)
        val rawWs = client.newWebSocket(request, capturingListener)
        return CapturingWebSocket(rawWs, repository, sessionId)
    }

    // DebugModule 接口实现
    override fun buildSettings() = emptyList<SettingGroup>()
    override fun createContentView(context: Context): View = ...
    override fun getBriefItems() = ...
    override fun onAttach(context: Context, storage: SettingsStorage) = ...
    override fun onDetach() = ...

    companion object {
        fun create(config: Config = Config()): NetworkCaptureModule
    }

    /** 数据治理与可配置项。 */
    data class Config(
        val maxHttpRecords: Int = 200,
        val maxWebSocketSessions: Int = 20,
        val maxFramesPerSession: Int = 500,
        val maxBodyBytes: Int = 64 * 1024,
        val maxFrameBytes: Int = 64 * 1024,
        val autoScrollPauseAfterUserScrollMs: Long = 3_000L
    )
}
```

### 业务方接入示例

```kotlin
// Application.onCreate
val capture = NetworkCaptureModule.create()

val client = OkHttpClient.Builder()
    .addInterceptor(capture.httpInterceptor())
    .build()

DebugTools.builder(this)
    .register(capture)
    .register( /* 其他模块 */ )
    .build()
```

```kotlin
// 业务原本写法
val ws = client.newWebSocket(request, listener)

// 改为
val ws = capture.newWebSocket(client, request, listener)
// 返回类型仍是 okhttp3.WebSocket，后续 ws.send / ws.close 等用法不变
```

业务方只需要修改 `newWebSocket` 调用点（一般每个项目 3-5 处）。

---

## 5. 拦截原理

### 5.1 HTTP 拦截：OkHttp Interceptor

WebSocket 握手本质是带 `Upgrade: websocket` 头的 HTTP GET 请求，普通 HTTP 和 WS 握手都通过 OkHttp Interceptor 拦截：

```kotlin
internal class CapturingInterceptor(
    private val repository: NetworkRepository
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNs = System.nanoTime()

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            repository.recordHttpFailure(request, e, System.nanoTime() - startNs)
            throw e
        }

        repository.recordHttp(request, response, System.nanoTime() - startNs)
        return response
    }
}
```

**关键点：**
- `chain.proceed()` 抛出 `IOException` 时也要记录失败（连接拒绝、DNS 失败、TLS 失败等）
- HTTP body 要克隆（OkHttp 的 `ResponseBody` 是一次性流，直接读完业务就拿不到了），用 `peekBody(maxBodyBytes)` 安全克隆
- WebSocket 握手在 `response.code == 101` 时识别，标记为 `isWebSocketUpgrade = true`，握手记录后续与 WS 会话关联

### 5.2 WebSocket 帧拦截：装饰器模式

OkHttp 的 WebSocket frame 不经过 Interceptor，必须装饰两个对象：

**装饰 WebSocketListener（接收方向）：**

```kotlin
internal class CapturingListener(
    private val delegate: WebSocketListener,
    private val repository: NetworkRepository,
    private val sessionId: String
) : WebSocketListener() {

    override fun onOpen(webSocket: WebSocket, response: Response) {
        repository.recordSessionOpen(sessionId, response)
        delegate.onOpen(webSocket, response)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        repository.recordFrame(sessionId, Direction.RECEIVE, FrameType.TEXT, text.toByteArray())
        delegate.onMessage(webSocket, text)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        repository.recordFrame(sessionId, Direction.RECEIVE, FrameType.BINARY, bytes.toByteArray())
        delegate.onMessage(webSocket, bytes)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        repository.recordSessionClosing(sessionId, code, reason)
        delegate.onClosing(webSocket, code, reason)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        repository.recordSessionClosed(sessionId, code, reason)
        delegate.onClosed(webSocket, code, reason)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        repository.recordSessionFailure(sessionId, t, response)
        delegate.onFailure(webSocket, t, response)
    }
}
```

**装饰 WebSocket（发送方向）：**

```kotlin
internal class CapturingWebSocket(
    private val delegate: WebSocket,
    private val repository: NetworkRepository,
    private val sessionId: String
) : WebSocket {

    override fun send(text: String): Boolean {
        val accepted = delegate.send(text)
        if (accepted) {
            repository.recordFrame(sessionId, Direction.SEND, FrameType.TEXT, text.toByteArray())
        }
        return accepted
    }

    override fun send(bytes: ByteString): Boolean {
        val accepted = delegate.send(bytes)
        if (accepted) {
            repository.recordFrame(sessionId, Direction.SEND, FrameType.BINARY, bytes.toByteArray())
        }
        return accepted
    }

    override fun close(code: Int, reason: String?): Boolean = delegate.close(code, reason)
    override fun cancel() = delegate.cancel()
    override fun request(): Request = delegate.request()
    override fun queueSize(): Long = delegate.queueSize()
}
```

**为什么 send 要先调 delegate 再记录？**  
因为 `send()` 返回 false 表示 OkHttp 内部队列已满，消息不会发出去——不应该记录。

### 5.3 时序数据采集：OkHttp EventListener

`addInterceptor` 拿不到细粒度的 HTTP 阶段耗时。要做 waterfall 时序图需要 `EventListener`：

```kotlin
internal class TimingEventListener(
    private val repository: NetworkRepository
) : EventListener() {
    private val timings = ConcurrentHashMap<Call, Timing.Builder>()

    override fun callStart(call: Call) {
        timings[call] = Timing.Builder(System.nanoTime())
    }
    override fun dnsStart(call: Call, domainName: String) { timings[call]?.dnsStart = System.nanoTime() }
    override fun dnsEnd(call: Call, ...) { timings[call]?.dnsEnd = System.nanoTime() }
    override fun connectStart(...) { ... }
    override fun secureConnectStart(...) { ... }  // TLS
    override fun requestHeadersStart(...) { ... }
    override fun responseHeadersStart(...) { ... }  // Time to first byte
    override fun callEnd(call: Call) {
        repository.attachTiming(call.request(), timings.remove(call)?.build())
    }
}
```

业务方接入时同时加上 EventListener.Factory（可选）：

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(capture.httpInterceptor())
    .eventListenerFactory(capture.eventListenerFactory())  // 可选，没加就拿不到 waterfall 数据
    .build()
```

EventListener 不是必须的——如果业务方没加，详情页的"时序"Tab 只显示总耗时，不显示阶段分解。文档里建议加。如果业务方已有自己的 EventListener，我们提供 `compose(yourFactory)` 链式包装 API 让两者并存。

---

## 6. 数据模型

```kotlin
internal data class HttpRecord(
    val id: String,                          // UUID
    val timestamp: Long,
    val method: String,
    val url: String,
    val protocol: String,                    // HTTP/1.1, HTTP/2
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: ByteArray?,
    val requestBodyTruncated: Boolean,
    val responseCode: Int,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: ByteArray?,
    val responseBodyTruncated: Boolean,
    val durationMs: Long,
    val timing: Timing?,                     // null 表示未启用 EventListener
    val failure: String? = null,             // 失败原因
    val isWebSocketUpgrade: Boolean,
    val webSocketSessionId: String? = null   // 关联的 WS 会话
)

internal data class WebSocketSession(
    val sessionId: String,
    val url: String,
    val handshakeRecordId: String,            // 关联 HttpRecord
    val openedAt: Long,
    var closedAt: Long? = null,
    var closeCode: Int? = null,
    var closeReason: String? = null,
    var failure: String? = null,
    val frames: MutableList<WebSocketFrame>   // 有上限，超出 LRU 驱逐
)

internal data class WebSocketFrame(
    val sessionId: String,
    val timestamp: Long,
    val direction: Direction,                 // SEND, RECEIVE
    val type: FrameType,                       // TEXT, BINARY, PING, PONG, CLOSE
    val size: Int,
    val payload: ByteArray?,                   // 截断后的内容
    val truncated: Boolean
)

internal data class Timing(
    val dnsMs: Long?,
    val connectMs: Long?,
    val tlsMs: Long?,
    val requestSendMs: Long?,
    val waitMs: Long?,                         // TTFB
    val responseReceiveMs: Long?,
    val totalMs: Long
)

enum class Direction { SEND, RECEIVE }
enum class FrameType { TEXT, BINARY, PING, PONG, CLOSE }
```

---

## 7. 数据流

```
业务代码                       NetworkCaptureModule              UI
─────────────────────────────────────────────────────────────────
client.newCall(req).execute()
     │
     ▼
CapturingInterceptor.intercept(chain)
     │  chain.proceed()
     ▼
真实 OkHttp 调用
     │  Response
     ▼
recordHttp(...) ─────► NetworkRepository (Dispatchers.IO)
                              │
                              │  StateFlow.update {...}
                              ▼
                       Presenter.collect
                              │
                              │  withContext(Dispatchers.Main)
                              ▼
                       view.appendRecord(...)
                              │  RecyclerView.submitList()
                              ▼
                       自动滚动到底 (如果用户没在拖)


业务调用 ws.send("hello")
     │
     ▼
CapturingWebSocket.send()
     │  rawWs.send() → true
     ▼
recordFrame(sessionId, SEND, TEXT, ...) ─► NetworkRepository
                                                  │
                                                  ▼
                                          流向 UI 同上
```

**线程模型：**
- Interceptor / WebSocket 回调可能在任意线程（OkHttp dispatcher / 网络线程）
- `NetworkRepository` 的写入操作内部加锁（`@Synchronized`）
- Presenter 用 `CoroutineScope(Dispatchers.IO)` 收集 Repository 的 `StateFlow`，处理完后 `withContext(Dispatchers.Main)` 更新 View
- UI 更新走 `ListAdapter` + `DiffUtil`，避免全量刷新闪烁

---

## 8. UI 设计

### 8.1 列表视图（混合 + 折叠）

```
┌──────────────────────────────────────────────────────┐
│ Tab: 网络抓包                                         │
├──────────────────────────────────────────────────────┤
│ 14:23:01  POST  /api/auth        200  127ms     ▶  │   ← HTTP
│ 14:23:02  WS    wss://api/sync   ●     3min24s  ▼  │   ← WS 会话（展开）
│   ├ 14:23:02.180  →  TEXT 234B  {"action":"sub..   │   ← 帧（点击展开）
│   ├ 14:23:02.450  ←  TEXT 156B  {"event":"ready"   │
│   ├ 14:23:02.510  →  BIN  42B   [hex preview]      │
│   ├ ... (更多帧自动加载)                              │
│   └ 14:26:26.123  ←  TEXT 89B   {"event":"msg",..  │
│ 14:26:30  WS    wss://llm/chat   ●ongoing       ▶  │
│ 14:23:08  POST  /api/profile     200  89ms      ▶  │
│                                                      │
│  ↓ 自动滚动跟随                                       │
└──────────────────────────────────────────────────────┘
                                            [📍 跟随最新]
```

**交互细节：**
- 列表追加到底部，新数据来时自动滚动到底
- 用户拖动时检测到 `ACTION_MOVE`，暂停自动滚动
- 停留 3 秒后恢复跟随（可配置 `autoScrollPauseAfterUserScrollMs`）
- 右下角 "📍 跟随最新" 浮动按钮：仅在暂停状态显示，点击立即跳到底部并恢复跟随
- 行高 ≥ 48dp（车机触控适配）
- 字体 16sp 起步
- WS 会话默认展开（流量小不会淹没），用户可手动折叠

**列表项一行内容：**

| 类型 | 字段 |
|---|---|
| HTTP | `时间 方法 URL路径 状态码 耗时` |
| WS 会话 | `时间 WS URL路径 ●状态 持续时间 帧数(可选)` |
| WS 帧 | `时间.毫秒 方向箭头 类型 大小 预览(TEXT前48字符)` |

状态色：
- 2xx：默认色
- 4xx：橙色 `#FBD38D`
- 5xx / 失败：红色 `#FC8181`
- WS 已建立：绿色 ● `#68D391`
- WS 已关闭：灰色 ⊘
- WS 异常关闭：红色 ⊘

### 8.2 HTTP 详情视图

点击 HTTP 行 → 全屏弹出（覆盖列表）：

```
┌──────────────────────────────────────────────────────┐
│ ← 返回    POST /api/auth                              │
├──────────────────────────────────────────────────────┤
│ [概览]  [请求]  [响应]  [时序]                         │
├──────────────────────────────────────────────────────┤
│                                                      │
│ 概览页：                                              │
│   方法：POST                                         │
│   URL：https://api.example.com/auth                 │
│   协议：HTTP/2                                       │
│   状态：200 OK                                        │
│   耗时：127ms                                         │
│   请求大小：512B                                      │
│   响应大小：1.2KB                                     │
│   时间：2026-06-12 14:23:01.234                      │
│                                                      │
│ 请求页：Headers (折叠) + Body (JSON 美化)             │
│ 响应页：Headers (折叠) + Body (JSON 美化)             │
│ 时序页：DNS / Connect / TLS / Send / Wait / Recv     │
│         水平柱状图                                    │
└──────────────────────────────────────────────────────┘
```

**Headers 折叠展开：**

```
[Request Headers (12)]               ▶  ← 默认折叠
[Request Headers (12)]               ▼  ← 展开后
  Content-Type: application/json
  Authorization: Bearer eyJhbGc...
  User-Agent: okhttp/4.12.0
  ...
```

**JSON 自动美化：**
- 检测 `Content-Type: application/json` 或 body 首字符是 `{` / `[`
- 用 `org.json.JSONObject` / `JSONArray` 解析后 `toString(2)` 缩进输出
- 解析失败则原文显示（保持容错）

**时序 Waterfall：**

```
DNS       ████ 12ms
Connect       ██████ 18ms
TLS                  ████████████ 45ms
Send                              █ 3ms
Wait                                ██████████████ 47ms
Receive                                            █ 2ms
                                                Total 127ms
```

横轴比例自适应；如果没启用 EventListener，时序页显示提示："启用 OkHttp EventListener 以查看分阶段耗时"。

### 8.3 WebSocket 详情视图

点击 WS 会话行（不是 frame）→ 全屏弹出：

```
┌──────────────────────────────────────────────────────┐
│ ← 返回    wss://api.example.com/sync                  │
├──────────────────────────────────────────────────────┤
│ [概览]  [握手]  [帧列表]  [统计]                        │
├──────────────────────────────────────────────────────┤
│ 概览页：                                              │
│   URL: wss://api.example.com/sync                   │
│   状态：● 已连接                                       │
│   建立时间：14:23:02.156                              │
│   持续：3min 24s                                      │
│   帧数：发 4520 / 收 3                                │
│   流量：发 17.8MB / 收 245B                           │
│                                                      │
│ 握手页：（标准 HTTP 详情结构，复用 HTTP 详情组件）       │
│ 帧列表页：（按时间排序，可点击单帧展开看 payload）        │
│ 统计页：（TEXT/BIN 占比、收发速率）                     │
└──────────────────────────────────────────────────────┘
```

### 8.4 帧详情（行内展开）

WS 帧在列表中点击直接行内展开：

```
14:23:02.180  →  TEXT  234B                          ▼
{
  "action": "start",
  "params": {
    "engine_type": "ws_v1",
    "result_type": "stream"
  }
}
```

二进制帧：

```
14:23:02.510  →  BIN  42B                            ▼
00000000  52 49 46 46 24 00 00 00  57 41 56 45 66 6d 74 20   RIFF$...WAVEfmt
00000010  10 00 00 00 01 00 01 00  80 3e 00 00 00 7d 00 00   .........>...}..
00000020  02 00 10 00 64 61 74 61                            ....data
```

---

## 9. 数据治理

### 9.1 内存上限（防 OOM）

| 项 | 默认上限 | 超出策略 |
|---|---|---|
| HTTP 请求列表 | 200 条 | LRU 驱逐最旧 |
| WS 会话列表 | 20 个 | LRU 驱逐最旧；驱逐时连同其 frames |
| 单 WS 会话 frame 数 | 500 帧 | 驱逐最旧的（保留最新） |
| 单 body 大小 | 64KB | 截断；标记 `truncated=true` |
| 单 frame 大小 | 64KB | 截断；标记 `truncated=true` |

所有上限通过 `NetworkCaptureModule.Config` 提供，业务方可改：

```kotlin
NetworkCaptureModule.create(
    Config(maxHttpRecords = 500, maxFramesPerSession = 1000)
)
```

### 9.2 截断策略

**HTTP body 截断：**
- 用 `response.peekBody(maxBodyBytes)` 安全读取（不消费原 ResponseBody）
- 如果实际长度 > maxBodyBytes，标记 `truncated=true`，详情页显示提示

**WS frame 截断：**
- TEXT：超出后取前 N 字节，按 UTF-8 边界对齐（避免半个字符乱码）
- BINARY：超出后取前 N 字节

**截断提示 UI：**

```
{
  "result": "...",
  "data": [
    ...
⚠ 内容已截断 (原始 512KB / 显示 64KB)
```

---

## 10. 与 core 模块的集成

`NetworkCaptureModule` 实现 `DebugModule` 接口：

- `moduleId = "debugtools_okhttp_capture"`
- `tabTitle = "网络抓包"`
- `buildSettings()`：V1 返回空（无可配置设置项）
- `createContentView(context)`：返回列表 View
- `getBriefItems()`：返回最新 1-2 条事件的摘要（如 "200 /api/auth", "WS · 87 帧"）
- `onAttach(context, storage)`：初始化 Presenter
- `onDetach()`：取消 CoroutineScope

**简要信息条**展示（在 BRIEF mode 下可见）：

```
HTTP 23 · WS 2(87f) · 1err
```

含义：23 条 HTTP / 2 个 WS 会话共 87 帧 / 1 个错误。

---

## 11. 性能考虑

- **Body 克隆使用 `peekBody`**：避免消费业务的 ResponseBody，零拷贝额外
- **截断在 IO 线程做**：超大 body 不阻塞 OkHttp 调度
- **UI 更新走 DiffUtil + 节流**：Repository 用 `MutableStateFlow` 暴露快照，Presenter 端用 `Flow.sample(100ms)` 节流，避免高频帧触发每帧刷新；UI 收到节流后的列表用 `ListAdapter.submitList()` 增量 diff
- **MinimizedMode 下不影响采集**：采集成本极低（仅写入内存），无需暂停；UI 刷新由 mode 控制（Minimized 下不更新 RecyclerView）
- **NetworkRepository 写入**：内部用 `synchronized` 保护各列表，单次写入是常数时间（追加 + 必要时驱逐最旧）
- **EventListener 数据收集用 `ConcurrentHashMap<Call, Timing.Builder>`**：每个 Call 隔离，无锁竞争
- **peekBody 限制**：OkHttp 的 `response.peekBody(maxBytes)` 会把响应缓冲到 maxBytes 字节（对流式响应这会强制加载）。对于车机端的端云业务请求，body 通常 < 10KB，maxBytes=64KB 影响可忽略；如果业务方有大文件下载场景，建议为该场景的 client 单独配置不带 capture 的实例

---

## 12. 错误处理

| 场景 | 处理 |
|---|---|
| Interceptor 抛异常 | catch 后记录失败，重新抛出（不改变业务行为） |
| WebSocketListener 异常 | 装饰器内 try-catch，记录失败后透传给原 listener |
| Body 解析失败（非法 JSON） | 显示原文，不阻塞详情页渲染 |
| EventListener 数据缺失 | 时序页显示"未启用 EventListener"，其他页正常 |
| Body 超大 | 截断到 maxBodyBytes，标记 truncated |
| Repository 写入时上限触发 | LRU 驱逐，无异常 |
| WS 高频帧写入 | Channel 缓冲，背压时丢弃最旧帧（保留最新） |

---

## 13. 已知限制

1. **三方 SDK 黑盒**：科大讯飞 MSC SDK、其他 native 网络栈无法抓
2. **业务方必须改 `newWebSocket` 调用点**：用 `capture.newWebSocket(...)` 代替 `client.newWebSocket(...)`
3. **EventListener 是可选的**：不加就没有时序 waterfall
4. **HTTPS 内容需要 OkHttp 解密后才能看到**：如果业务用了 cert pinning 失败也能看到失败原因，但不能解密被拦截的请求
5. **WebSocket frame payload 全部存内存**：单会话上限 500 帧 × 64KB = 32MB 极端值；典型场景远低于此
6. **不持久化**：App 退出后数据丢失（V2 可加文件持久化）
7. **不支持 Ktor / HttpURLConnection / gRPC**：仅 OkHttp 4.x
