# debugtools-logcat 设计文档

> **状态**：初稿，待用户评审通过后进入实现 plan 阶段。

## 1. 目标与范围

新增 `debugtools-logcat` 可选 Android library 模块，在 DebugTools SDK 悬浮窗里加一个"日志"tab，让集成方在设备上实时查看本 app（同 UID）所有进程的 logcat，按 Level 过滤、能"开始/停止录制"并把录制段导出/分享。

**核心使用场景**：
- 座舱里语音助手出问题时，无需连 adb 直接在车机屏上看 ASR/NLU/TTS/VAD 等模块的日志输出。
- 复现到 bug 当下点 `▶ 开始录制`，重现完点 `■ 停止录制 (38s, 412 条)`，文件秒发到 IM/邮箱。

**不在本模块范围内**：
- 跨 UID 的全系统日志（需要 `READ_LOGS` 系统权限，本模块默认不依赖；后续可以 v1.1 加 `system` 模式开关）。
- Tag/进程名/关键字搜索过滤（v1 只做 Level 过滤，保持最小可用形态）。
- 远程日志上报、ELK 联动等高级能力。

---

## 2. 模块布局

```
debugtools-logcat/                 ← 新模块，可选
├── build.gradle.kts
└── src/main/kotlin/com/debugtools/logcat/
    ├── LogcatModule.kt            ← DebugModule 入口 + Builder
    ├── Config.kt
    ├── data/
    │   ├── LogLine.kt
    │   └── LogLevel.kt
    ├── source/
    │   ├── LogcatProducer.kt      ← 子进程 + stdout 读线程
    │   └── LogcatParser.kt        ← `-v threadtime` 单行解析
    ├── repository/
    │   └── LogRepository.kt       ← ringbuffer + Snapshot StateFlow
    ├── recorder/
    │   └── LogRecorder.kt         ← 临时文件 + share intent
    ├── presenter/
    │   ├── LogcatView.kt
    │   └── LogcatPresenter.kt
    └── view/
        ├── LogcatRootView.kt
        ├── LogToolbarView.kt      ← chips + 录制按钮
        ├── LogListView.kt
        └── LogDetailOverlay.kt
```

依赖 `:debugtools-core`、`kotlinx-coroutines-android:1.7.3`、`androidx.annotation`。测试侧 `junit:4.13.2 + robolectric:4.11.1 + kotlinx-coroutines-test:1.7.3`，沿用现有模块的版本。

---

## 3. 公共 API

```kotlin
DebugTools.builder(context)
    .register(
        LogcatModule.builder()
            .bufferSize(5000)                        // 默认 5000，clamp 500..50000
            .defaultLevels(setOf(LogLevel.I, LogLevel.W, LogLevel.E))  // 默认全 5 级
            .maxRecordingMb(20)                      // 默认 20，clamp 1..200
            .build()
    )
    .build()
```

- `moduleId = "debugtools_logcat"`, `tabTitle = "日志"`
- `LogcatModule.Builder` 各方法 `coerceIn` 范围内（参考 PerfMonitorModule 的 Builder 模式）。
- 用户对 chip / 录制状态的临时操作不持久化（清空、Level 选择都是会话内）；只有 Builder 参数才用于初始化。

---

## 4. 架构与数据流

```
logcat 子进程 (Runtime.exec)
     │ stdout (按行)
     ▼
LogcatProducer (后台线程读)
     │ 解析后的 LogLine
     ▼
LogcatParser (静态函数)
     │
     ▼
LogRepository.append(line)
     │ ringbuffer (5000 条) + @Synchronized 写
     │ 同步通知 LogRecorder（如果正在录制，写文件）
     ▼
StateFlow<Snapshot(lines, recording?, tick)>
     │
     ▼
LogcatPresenter (combine state + filter)
     │ sample(200ms) 节流
     ▼
LogcatView (toolbar + list + overlay)
```

**线程模型**：
- LogcatProducer 用一个独立 `Thread`（不是 coroutine）跑阻塞 `BufferedReader.readLine()`，简单且能直接 join cancel。
- Presenter 在 `Dispatchers.Main + SupervisorJob` 上 collect。
- Repository 用 `@Synchronized` 保护 ringbuffer + recorder 注册的写文件回调。

**为什么 producer 用 Thread 而不是 IO coroutine**：阻塞 `readLine` 在 cancel 时无法被打断；用 `Thread + Process.destroy()` 能立即关闭子进程进而让 readLine 返回 EOF，是最干净的退出路径。

---

## 5. 数据采集（LogcatProducer + LogcatParser）

### 5.1 启动子进程

```kotlin
val process = ProcessBuilder("logcat", "-v", "threadtime")
    .redirectErrorStream(true)
    .start()
```

- `-v threadtime` 格式：`MM-DD HH:MM:SS.mmm  PID  TID L Tag: message`
- `redirectErrorStream` 把 stderr 并到 stdout，简化处理。
- 启动后台 thread 持续 `readLine()`，每行交给 `LogcatParser.parse(line)` 解析。

### 5.2 解析器

`LogcatParser.parse(raw: String): LogLine?`：
- 正则 `^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+([^:]+?):\s?(.*)$`
- 不匹配的行（多行 trace 续行、空行）返回 null。
- 把日期 + 时间拼成当前年的 `Long timestampMs`（不依赖当前年——logcat 不输出年份，假定都是当前年份，新年跨年小概率失真可接受）。

### 5.3 子进程死亡处理

- `readLine() == null` → EOF → 视为子进程被系统杀。
- 自动重启一次，间隔 1 秒；连续 3 次失败则进入 `ProducerDied` 状态，停止重试，UI 显示 banner。
- 重启后会丢失之前的内核 buffer 内容，但已采集的 ringbuffer 不丢。

---

## 6. 数据模型

```kotlin
enum class LogLevel(val code: Char) {
    VERBOSE('V'), DEBUG('D'), INFO('I'), WARN('W'), ERROR('E');
    companion object {
        fun fromCode(c: Char): LogLevel? = values().firstOrNull { it.code == c }
    }
}

data class LogLine(
    val timestamp: Long,    // millis
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String
)

data class Snapshot(
    val lines: List<LogLine>,          // 当前 ringbuffer 的不可变快照
    val recording: RecordingState?,    // null = 未录制
    val producerAlive: Boolean,        // false → UI 显示 banner
    val tick: Long = 0L                // 每次 publish 自增，防 StateFlow dedup（perfmon 教训）
)

data class RecordingState(
    val startedAt: Long,
    val countSoFar: Int,
    val tempFile: java.io.File
)
```

只保留 5 个标准 level，不收 F (Fatal) 也不收 S (Silent) ——F 极罕见，归到 E 处理（实际 logcat 流里几乎看不到）；S 是过滤标记不是真级别。

---

## 7. Repository（LogRepository）

```kotlin
class LogRepository(private val config: Config) {
    private val buffer = ArrayDeque<LogLine>(config.bufferSize)
    private var recorder: LogRecorder? = null
    private val _state = MutableStateFlow(Snapshot(...))
    val state: StateFlow<Snapshot> = _state
    private var tick: Long = 0L

    @Synchronized
    fun append(line: LogLine) {
        if (buffer.size >= config.bufferSize) buffer.removeFirst()
        buffer.addLast(line)
        recorder?.write(line)
        publish()
    }

    @Synchronized
    fun startRecording(file: File): RecordingState { ... }

    @Synchronized
    fun stopRecording(): File? { ... }

    @Synchronized
    fun clear() { buffer.clear(); publish() }

    @Synchronized
    fun markProducerDied() { ... }
}
```

`publish()` 总是构造一个新 `Snapshot(lines = buffer.toList(), ..., tick = ++tick)`，确保 StateFlow 重发射（**直接复用 perfmon 修复的模式**：data class + 内部含可变引用容易被 dedup，必须用 tick 兜底）。

---

## 8. 录制（LogRecorder）

- 临时文件位置：`context.cacheDir/debugtools/logcat-YYYYMMDD-HHMMSS.log`
- 文件格式：每行直接写 `LogLine.toString()` 形如 `06-15 14:23:01.123 12345 12350 I MyTag: hello world\n`（保持 logcat 原始可读格式）。
- 录制中：每个 `append(line)` 同步追加；用 `BufferedWriter` 减少 IO。
- `stopRecording()`：
  - 关闭 writer
  - 通过 `FileProvider` 暴露 URI（需要 manifest 声明 provider authority — 由集成方提供，模块文档里写明）
  - 触发 `ACTION_SEND` chooser intent，让用户选择分享渠道（IM、邮箱、保存到 Downloads 等）
- **大小上限**：每 append 检查文件大小（writer 内部计数，避免每次 stat 文件），超 `config.maxRecordingMb` 自动 `stopRecording()` 并在 UI 提示 "已达 20MB 上限，自动停止"。

---

## 9. UI

### 9.1 LogcatRootView 结构

```
┌────────────────────────────────────┐
│ LogToolbarView (高度 ~64dp)        │
│ [V][D][I][W][E]  [▶/■]  [🗑]      │
├────────────────────────────────────┤
│                                    │
│ LogListView (ScrollView)           │
│  HH:MM:SS L Tag: message          │
│  ...                               │
│                                    │
│             ┌──────────┐           │
│             │ ↓ Resume │ (浮动)    │
│             └──────────┘           │
└────────────────────────────────────┘
```

如果 `producerAlive == false`，toolbar 下方插一行红色 banner: `⚠ logcat 子进程异常，已停止采集（点击重试）`，点击重新启动 producer。

### 9.2 LogToolbarView

- 5 个 Level chip 横排：背景色按 chip 自身代表的 level 染（V 灰 / D 蓝 / I 绿 / W 橙 / E 红），选中态加亮，未选态降饱和。
- 录制按钮：未录制时显示 `▶ 开始录制`；录制中显示 `■ 停止录制 (38s, 412 条)` 并按秒更新。
- 清空按钮：弹一个 toast 确认 + 立即 `repository.clear()`。

### 9.3 LogListView

- 自动滚动：默认开，新行追加后 `scrollTo(bottom)`。
- 用户上滑触摸时停止自动滚动（通过 `onTouchEvent` 检测）；当 scroll 到底部 ± 20px 范围内时恢复自动滚动。
- "↓ Resume" 浮动按钮在非自动滚动状态显示，点击直接跳底部 + 重新开自动滚。
- 每行 `TextView`：单行截断（`maxLines = 1, ellipsize = END`），点击弹 detail overlay。
- 颜色：按 level，message 字色比 tag 略弱（`#E2E8F0` vs `#A0AEC0`）。

### 9.4 LogDetailOverlay

参考 okhttp-capture 的 detail overlay 实现：全屏 `FrameLayout`，半透明黑底，中央卡片展示完整的 pid/tid/level/tag/timestamp/message，底部 `复制` + `关闭` 按钮（同 okhttp-capture）。

---

## 10. 性能预算

| 项目 | 估算 | 备注 |
|---|---|---|
| logcat 子进程 | 几 MB 常驻 + ~1% CPU | 系统自带 binary，无优化空间 |
| Java 侧读线程 | 解析 1 行 ~10us，1000 行/s 约 1% CPU | 正则匹配是热点，必要时换手写 |
| Ringbuffer 内存 | 5000 × ~200 字节 ≈ 1MB | LogLine 是 data class，引用一份 String |
| Repository publish 频率 | 等同 append 频率 | 高频日志会让 StateFlow 频繁发射 |
| UI 渲染 | sample(200ms) 节流 + addView 重建（< 5000 行） | 5000 行 ScrollView 在车机屏可接受 |

**节流原因**：log 高峰时单秒上千行，UI 每秒重排 1000 次直接卡死。`sample(200ms)` 让 view 每 200ms 最多拿一次最新 snapshot，丢失中间帧但不影响"看实时日志"目标。

---

## 11. 错误处理与边界

| 情形 | 处理 |
|---|---|
| logcat 子进程启动失败 | catch IOException → ProducerDied → banner 提示 |
| 子进程 EOF（被杀） | 等 1s 重启 1 次，连续 3 次失败转 ProducerDied |
| readLine 抛 IOException | 视同 EOF 走重启逻辑 |
| 解析失败的行 | LogcatParser 返回 null → 丢弃 |
| 录制时磁盘满 | catch IOException → 停止录制 + UI 提示 |
| 录制超过 maxRecordingMb | 自动 stopRecording + 提示 + 弹分享 |
| Snapshot dedup | tick 字段兜底，参考 perfmon 修复 |
| 没配 FileProvider | recorder 在分享时 catch → 提示 "需要在 AndroidManifest 注册 FileProvider"，并保留临时文件给用户手动取 |

---

## 12. 测试策略

| 测试 | 类型 | 覆盖点 |
|---|---|---|
| `LogcatParserTest` | 纯 JVM | 标准行、tag 含冒号、message 含冒号、不匹配行、各 level |
| `LogRepositoryTest` | 纯 JVM | append + ringbuffer 淘汰、tick 单调递增、startRecording 写文件、 maxRecordingMb 触发自动停止 |
| `LogcatPresenterTest` | 纯 JVM | level filter 生效、producerAlive 切换、sample(0) 透传 |
| `LogcatModuleTest` | Robolectric | Builder clamp 范围、moduleId/tabTitle |
| `LogcatProducer` | 不写自动化测试 | 依赖真 logcat 子进程；改在 demo 集成里手动验 |

参考 perfmon 教训：所有 StateFlow 行为测试要**订阅式**（`flow.toList(emissions)`），不能只读 `state.value`。

---

## 13. 已知限制

1. 跨 UID 看其他 app 日志：不支持。集成方在系统应用环境可考虑后续加一个 `system = true` 模式开关来 spawn `logcat *:V`（依赖 `READ_LOGS`）。
2. 跨年份时间戳精度：logcat 不输出年份，模块假定都是当前年；跨年那一刻可能少数行被打成上一年时间。可接受。
3. Multi-line stacktrace 续行：当前每行独立解析，stack trace 看起来是一堆零散行（带 `at xx.xx.xx` 前缀的行）。完整 stack 可由集成方在 Crash 模块（下一个项目）专门处理。
4. 中文乱码：依赖系统 logcat 默认 UTF-8 输出。若设备系统切换 locale 出现乱码（极罕见）需要在 ProcessBuilder 上明确 `environment["LANG"] = "en_US.UTF-8"`。
