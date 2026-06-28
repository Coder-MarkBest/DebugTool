# 设计：App 启动链路监控模块（debugtools-startup）

日期：2026-06-28
模块：新增 `debugtools-startup`（可选模块，依赖 `debugtools-core`）
状态：已批准设计要点，待写实现计划

## 1. 背景与目标

App 启动时要初始化很多组件,组件之间可能有依赖关系。需要监控**从进程启动到初始化完成**期间每个组件的初始化结果(成功/失败)、流程(依赖)、耗时,并持久化以便回看与横向对比"这次启动为什么慢/挂"。

**职责划分(核心理念):** DebugTool 定义一套**通用初始化协议**,接入方按协议上报;DebugTool 对组件语义零理解,只据协议字段做记录、判定与展示。

## 2. 范围

**做:** 协议 + 上报 API + 持久化(最近 10 次,App 私有目录,卸载即删) + 双视图(甘特 / 依赖图)+ 自动诊断。

**不做(YAGNI / 已确认):**
- 不做网络上报(只本地持久化)。
- 不做跨进程。
- step 不嵌套子 step(扁平 + 显式依赖)。
- 不自动接 `androidx.startup`(V1 走协议主动上报)。
- step 状态只 `RUNNING / SUCCESS / FAILED`("卡死/漏 end"归到诊断,不单设 TIMEOUT 状态)。

## 3. 协议(数据契约)—— 核心

```kotlin
enum class StepStatus { RUNNING, SUCCESS, FAILED }

/** 一个初始化步骤(组件)。会话内 [name] 唯一。 */
data class StartupStep(
    val name: String,
    val dependsOn: List<String>,   // 前置 step 的 name
    val startUptimeMs: Long,       // SystemClock.uptimeMillis() at begin
    val endUptimeMs: Long?,        // null = 进行中(RUNNING)
    val status: StepStatus,
    val error: String?,            // FAILED 时存 "类名: message\n首若干行堆栈"
    val thread: String             // begin 所在线程名(看并行)
)

/** 一次 App 启动的完整会话。 */
data class StartupSession(
    val sessionId: String,         // UUID
    val startedAtWallMs: Long,     // System.currentTimeMillis(),仅用于列表排序/显示
    val launchUptimeMs: Long,      // t0:进程启动时间(Process.getStartUptimeMillis(), API24+)
    val appVersion: String?,       // 可选,便于跨版本对比
    val steps: List<StartupStep>,
    val completedUptimeMs: Long?,  // 整体完成时刻;null = 未显式完成(兜底 finalize)
    val completedExplicitly: Boolean // true=调了 complete();false=兜底收尾
)
```

**时间口径:** 一律用 **`SystemClock.uptimeMillis()`**(单调,免受系统改时间影响)。每个 step 在甘特上的 X 位置 = `startUptimeMs − launchUptimeMs`。t0 取**进程启动时间**(`Process.getStartUptimeMillis()`),覆盖"SDK 还没初始化前"的那段。

## 4. 上报 API（接入方使用）

`AppStartupMonitor` 是一个**进程内单例**,宿主在 `Application.onCreate()` 尽早 `init(context)`(拿到落盘目录),随后从任意线程上报。

```kotlin
object AppStartupMonitor {
    /** 设定落盘目录(= context.filesDir/startup),开始一次新会话。幂等:重复调忽略。 */
    fun init(context: Context, appVersion: String? = null)

    /** 可选:自定义 t0(默认用进程启动时间)。 */
    fun markLaunch()

    // —— 协议三件套(必需,线程安全) ——
    fun begin(name: String, dependsOn: List<String> = emptyList())
    fun success(name: String)
    fun fail(name: String, error: Throwable)        // 报错也算结束
    fun fail(name: String, errorMessage: String)    // 无 Throwable 时

    /** 便捷壳(同步/挂起初始化):自动计时 + 正常返回记 success + 抛异常记 fail 并原样 rethrow。 */
    inline fun <T> track(name: String, dependsOn: List<String> = emptyList(), block: () -> T): T

    /** 标记"初始化完成":定义启动终点 + 触发诊断与落盘。 */
    fun complete()

    /** 同进程内给 module 读"当前(可能进行中)会话"用。 */
    fun currentSession(): StartupSession?
}
```

`track` 等价于手写 `begin → try{block; success} catch{fail; throw}`;**异步/回调式**初始化不能用 track,用 `begin` + 回调里 `success/fail`。

**线程安全:** 内部用 `synchronized` 守护一个 `name → StartupStep` 的可变表;`begin/success/fail` 可并发调用。重复 `begin` 同名 step、对未 begin 的 step 调 `success/fail` 都安全忽略(不抛)。

**完成与兜底:** `complete()` 是主信号(总耗时 = `completedUptimeMs − launchUptimeMs`)。若宿主漏调:
- `AppStartupMonitor` 在 `init` 时 `registerActivityLifecycleCallbacks`,**首个 Activity 的 onResume** 作为兜底,延迟 ~1s 后若仍未 `complete()` 则自动 finalize(`completedExplicitly=false`),保证数据不丢。

## 5. 持久化（卸载即删）

`StartupStore`:
- 目录 `context.filesDir/startup/`(**App 私有,卸载自动清除**,无需额外删除逻辑)。
- 每次会话 finalize 时写一个 JSON 文件 `<startedAtWallMs>_<sessionId>.json`(用 `org.json`,不引生产依赖;测试加 `org.json:json`)。
- 只保留**最近 10 次**:写入后按文件名(含时间戳)排序,删除超出的最旧文件。
- 读取:列出目录、解析为 `List<StartupSession>`(最近在前)。解析失败的单个文件跳过(不影响其它)。

## 6. 模块与视图

`StartupMonitorModule : DebugModule`,`tabTitle = "启动链路"`。`onAttach` 拿 context → `StartupStore`。`createContentView` 渲染:

- **会话列表**:`StartupStore` 的持久化会话 + `AppStartupMonitor.currentSession()`(本次启动,可能进行中)合并,最近在上。每条:开始时间、总耗时、`✓N ✗M`、是否显式完成。点进某次 →
- **甘特图视图(默认)**:时间轴(0 → 总耗时);每 step 一行,条形 `start→end`,色=状态(绿成功/红失败/灰进行中);并行 step 条形在时间轴上自然重叠;**依赖箭头**(从依赖项 end 指到本 step start);**关键路径高亮**。点 step 展开错误详情/堆栈。
- **依赖图视图(DAG)**:一键切换;节点=组件(色=状态),边=依赖;按拓扑分层布局。
- **顶部摘要**:总启动耗时、step 数、失败数、关键路径。

视图配色复用一套 `StartupColors`(纯 ARGB Int,可测),风格与 audiomon 面板一致(深色卡片)。

## 7. 自动诊断（纯逻辑,可测）—— 定位价值

`StartupAnalyzer.analyze(session): List<StartupIssue>`,纯函数。规则:

| 类型 | 判定 | 默认阈值 |
|---|---|---|
| `ERROR` 初始化报错 | step.status == FAILED | — |
| `SLOW` 慢组件 | 完成的 step 耗时 > 阈值 | **50ms** |
| `DEP_VIOLATION` 依赖倒挂 | step.start < 它任一依赖项的 end | — |
| `NEVER_ENDED` 卡死/漏 end | 会话完成时 step 仍 RUNNING | — |
| `DEP_CYCLE` 依赖环 | dependsOn 图存在环 | — |
| `PARALLELIZABLE` 可并行却串行 | 两 step 间无依赖路径,却严格先后(B.start ≥ A.end) | 仅提示(info) |

`StartupIssue(type, stepName?, detail, severity)`。另算 **关键路径**:`criticalPath(session): List<String>`——定义为以"最晚结束的 step"为终点,沿 `dependsOn` 反向走、每步选择"其依赖中 `endUptimeMs` 最大(最晚完成)"的那个前置,直到无依赖;返回从起点到终点的 step 名链。即决定总启动耗时的那条依赖链。无依赖时返回单元素链。

`StartupAnalyzer` 与 `criticalPath` 全部纯 JVM,合成会话单测(规则边界 + 关键路径)。

## 8. 组件 / 文件清单（预估）

新增模块 `debugtools-startup`:
- `protocol/StartupStep.kt`、`protocol/StepStatus.kt`、`protocol/StartupSession.kt`(+ JSON 序列化)
- `protocol/StartupIssue.kt`
- `AppStartupMonitor.kt`(单例:协议三件套 + track + complete + 兜底)
- `StartupStore.kt`(落盘/读取/LRU)
- `analyzer/StartupAnalyzer.kt`、`analyzer/CriticalPath.kt`(纯逻辑)
- `StartupMonitorModule.kt`(DebugModule 入口)
- `view/StartupColors.kt`、`view/SessionListView.kt`、`view/GanttView.kt`、`view/DagView.kt`、`view/StartupRootView.kt`(列表/甘特/DAG/切换容器 + 诊断面板)
- 测试:`StartupSessionJsonTest`、`StartupAnalyzerTest`、`CriticalPathTest`、`StartupStoreTest`(JSON 往返 + LRU)
- `build.gradle.kts`(android lib;`testImplementation org.json:json`、robolectric 视需要)、`AndroidManifest.xml`
- `settings.gradle.kts` 加 `:debugtools-startup`

## 9. 测试

- 协议 JSON 往返;`AppStartupMonitor` 的并发 begin/重复 begin/未 begin 调 success 等边界(纯逻辑部分,可不依赖 Android)。
- `StartupAnalyzer`:报错、慢(>50ms)、依赖倒挂、卡死、依赖环、可并行却串行 各一例;`criticalPath` 已知图验证。
- `StartupStore`:写入/读取/LRU 截断(用 `@get:Rule TemporaryFolder` 模拟 filesDir)。
- 视图为 Android 视图,纯绘制,不单测。

## 10. 已知约束 / 取舍

- 仅同进程(`currentSession()` 跨进程不可见;持久化数据跨进程可读)。
- step 状态三态;"卡死"靠诊断,不单设 TIMEOUT。
- 慢阈值默认 50ms(可配)。
- 保留最近 10 次会话。
- t0 = 进程启动时间;`complete()` 漏调有 onResume 兜底。
- 不做网络上报(纯本地)。
