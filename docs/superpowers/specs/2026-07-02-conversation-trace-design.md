# 设计：对话链路追踪模块（debugtools-conversation）

日期：2026-07-02
模块：新增 `debugtools-conversation`（可选模块，依赖 `debugtools-core`）
状态：已批准设计，待写实现计划

## 1. 背景与目标

语音助手 App 每次对话从"唤醒"到"TTS 播报/执行"经历多个阶段（唤醒→VAD→ASR→NLU→对话管理→技能路由→TTS→执行），出现问题后日志分散、难以快速定位"哪一轮的哪个阶段挂了"。

**职责划分（与启动监控一致的核心理念）：** DebugTool 定义一套**通用对话轨迹协议**，接入方已有完整链路日志，写一个**适配层**把现有日志映射到协议字段 → `submitTurn(turn)` 提交。DebugTool 对阶段语义零理解，只据协议字段做记录、展示与诊断。

## 2. 范围

**做：** 协议（Turn + Stage + Session）+ 上报 API（startSession / submitTurn / endSession）+ 持久化（最近 50 次对话会话，App 私有目录，卸载即删）+ 三层视图（会话列表→轮次列表→轮次详情含阶段时间线）+ 自动诊断。

**不做（YAGNI / 已确认）：**
- 不做 confidence 字段（TurnStage 移除）。
- 不做流式逐阶段上报（只整轮提交 `submitTurn`）。
- 不做网络上报。
- 不做跨进程。
- 不在本模块做快照录制（后续独立模块聚合）。

## 3. 协议（数据契约）

```kotlin
// ── 阶段状态 ──
enum class StageStatus { RUNNING, SUCCESS, FAILED, SKIPPED }

// ── 一轮内的一个阶段 ──
data class TurnStage(
    val name: String,              // "ASR"/"NLU"/"TTS"/"执行"…完全由接入方自定
    val startOffsetMs: Long,       // 相对于本 Turn 开始的偏移(便于甘特对齐),≥0
    val endOffsetMs: Long?,        // null = 进行中
    val status: StageStatus,
    val input: String?,            // 该阶段的输入(文本或 JSON),可空
    val output: String?,           // 该阶段的输出,可空
    val error: String?,            // FAILED 时的错误信息
    val thread: String             // 执行线程名
)

// ── 轮次结果 ──
enum class TurnOutcome { SUCCESS, FAILED, ABORTED, TIMEOUT }

// ── 一轮对话 ──
data class ConversationTurn(
    val turnId: String,            // UUID,唯一标识
    val turnIndex: Int,            // 第几轮(从 1 开始)
    val sessionId: String,         // 属于哪次对话
    val startUptimeMs: Long,       // 本轮的 SystemClock.uptimeMillis()
    val endUptimeMs: Long?,        // null = 进行中
    val userInput: String?,        // ASR 最终识别文本 / 手动输入
    val stages: List<TurnStage>,   // 有序,按 startOffsetMs 升序
    val outcome: TurnOutcome,
    val tags: List<String>?        // 可选:["导航","音乐","空调"],适配层自填
)

// ── 一次对话会话(多轮) ──
data class ConversationSession(
    val sessionId: String,
    val startedAtWallMs: Long,
    val metadata: Map<String, String>?,   // {"scene": "导航中", "userId": "xxx"}
    val turns: List<ConversationTurn>,
    val endedAtWallMs: Long?              // null = 仍在进行
)
```

**时间口径：** Turn 用 `SystemClock.uptimeMillis()` 绝对时间；Stage 用 `startOffsetMs/endOffsetMs` 相对 Turn 开始时间的偏移，适配层不用操心全局对齐。

## 4. 上报 API（接入方使用）

`ConversationTracer` 是一个**进程内单例**，适配层调用。

```kotlin
object ConversationTracer {
    /** 设定落盘目录。多调忽略。 */
    fun init(context: Context)

    /** 开始一次新的对话会话。不强制调——不调 startSession 时 submitTurn 懒创建 session。 */
    fun startSession(sessionId: String, metadata: Map<String, String>? = null)

    /** 提交完整的一轮(适配层组装好,只调这一次)。线程安全。 */
    fun submitTurn(turn: ConversationTurn)

    /** 结束本次对话会话,触发持久化 + LRU。 */
    fun endSession(sessionId: String)

    /** 给 DebugModule 读当前(可能进行中)会话用。 */
    fun currentSession(): ConversationSession?

    /** 读取所有已持久化的会话(历史,最近在前)。 */
    fun loadSessions(): List<ConversationSession>
}
```

**线程安全：** 内部 `synchronized`。重复 `startSession` / `endSession` 幂等。对不存在的 session 调 `submitTurn` 自动懒创建 session。

**生命周期兜底：** 同 `AppStartupMonitor`——`init` 时注册 ActivityLifecycleCallbacks，长时间无 `endSession` 后自动 finalize（`endedAtWallMs` = 最后轮次的 `endUptimeMs` 对应 wall time），保证数据不丢。

## 5. 持久化

`ConversationStore`：
- 目录 `context.filesDir/conversation/`，App 私有，卸载自动清除。
- `endSession` 时写一个 JSON 文件 `<startedAtWallMs>_<sessionId>.json`（使用 `org.json`，不引入生产依赖；测试加 `org.json:json`）。
- 保留**最近 50 次**对话会话：写入后按文件名排序，超出 50 的最旧文件删除。
- 读取：列出目录、解析为 `List<ConversationSession>`（最近在前）。解析失败的单个文件跳过。

## 6. 视图（三层导航）

`ConversationMonitorModule : DebugModule`，`tabTitle = "对话链路"`。

| 层级 | 视图 | 内容 |
|------|------|------|
| L1 | 会话列表 | 最近 50 次对话。每条：开始时间、轮数、✓/✗ 轮数、总耗时。点进某次 → |
| L2 | 轮次列表 | 该会话所有轮次。每轮一行：轮次号 `#N`、截断的 userInput（最多 20 字）、色标 outcome（绿成功/红失败/黄超时/灰中断）、耗时。点进某轮 → |
| L3 | 轮次详情 | (1) **阶段时间线**：从左到右按 offset 排列，每个 Stage 色块（绿成功/红失败/灰跳过），块上写阶段名，失败阶段红框高亮。(2) **阶段展开区**：选中/默认展开第一个失败阶段的 `input`/`output`/`error`。(3) **诊断列表**：`TurnAnalyzer` 发现的问题。(4) 顶部：轮次号 + outcome + userInput + 总耗时。 |

导航：L1→L2→L3，每层有返回按钮。配色复用项目既有深色卡片风格。

## 7. 自动诊断（纯逻辑，可测）

`TurnAnalyzer.analyze(turn): List<TurnIssue>`：

| 类型 | 判定 | 默认阈值 |
|------|------|----------|
| `STAGE_FAILED` | stage.status == FAILED | — |
| `SLOW_STAGE` | 已结束 stage 耗时 > 阈值 | **500ms**（对话阶段比启动宽容） |
| `TURN_TIMEOUT` | outcome == TIMEOUT | — |
| `TURN_ABORTED` | outcome == ABORTED | — |
| `PIPELINE_GAP` | stage[N].startOffsetMs > stage[N-1].endOffsetMs（阶段间有空隙） | 仅 info |

`TurnIssue(type, stageName?, detail, severity)`。

## 8. 组件 / 文件清单

新增模块 `debugtools-conversation`：

```
protocol/
  StageStatus.kt
  TurnStage.kt
  TurnOutcome.kt
  ConversationTurn.kt
  ConversationSession.kt
  TurnIssue.kt
ConversationTracer.kt          — 单例(startSession/submitTurn/endSession + 兜底)
store/ConversationStore.kt     — 落盘/读取/LRU 50
analyzer/TurnAnalyzer.kt      — 纯诊断
view/
  ConversationColors.kt
  SessionListView.kt           — L1: 会话列表
  TurnListView.kt              — L2: 轮次列表
  TurnDetailView.kt            — L3: 阶段时间线 + 展开区 + 诊断
  ConversationRootView.kt      — 三层导航容器
ConversationMonitorModule.kt   — DebugModule 入口
```

测试：
- `ConversationSessionJsonTest` — Turn/Session JSON 往返
- `TurnAnalyzerTest` — 各诊断规则 + 边界
- `ConversationStoreTest` — 写入/读取/LRU/文件损坏跳过

修改：
- `settings.gradle.kts` 加 `:debugtools-conversation`
- `app/build.gradle.kts` 加依赖

## 9. 测试

- 协议 JSON 往返：整轮含 stages 序列化/反序列化，null 字段复原。
- `TurnAnalyzer`：阶段失败、慢阶段(>500ms)、轮超时、轮中断、流水线间隙各一例。
- `ConversationStore`：写入/读取/LRU 50 / 损坏文件跳过（用 `TemporaryFolder`）。
- 视图为 Android Canvas，不单测。

## 10. 已知约束 / 取舍

- 仅同进程上报。数据存 `filesDir/conversation`，保留最近 50 次，卸载即删。
- Stage 名不限枚举，接入方自定；Tool 只展示不校验。
- 不做 confidence。
- 不做跨进程。
- 不做流式上报，只整轮提交。
- 慢阶段阈值默认 500ms（可配）。
- 后续快照录制模块会聚合本模块数据作为导出包的一部分。

## 11. 适配层示意（接入方实现，不在本模块内）

```kotlin
// 接入方把自己的链路日志映射到协议
fun adaptLogToTurn(rawLog: MyVoiceLog): ConversationTurn {
    return ConversationTurn(
        turnId = rawLog.traceId,
        turnIndex = rawLog.roundIndex,
        sessionId = rawLog.dialogId,
        startUptimeMs = rawLog.startTime,
        endUptimeMs = rawLog.endTime,
        userInput = rawLog.asrFinalText,
        stages = listOf(
            TurnStage("唤醒", 0, 200, SUCCESS, null, null, null, "main"),
            TurnStage("ASR", 200, 600, SUCCESS,
                "[audio pcm]", rawLog.asrFinalText, null, "asr-thread"),
            TurnStage("NLU", 600, 650, SUCCESS,
                rawLog.asrFinalText, rawLog.nluJson, null, "nlu-thread"),
            // ...TTS, 执行等
        ),
        outcome = if (rawLog.error == null) TurnOutcome.SUCCESS else TurnOutcome.FAILED,
        tags = rawLog.domains  // ["导航"]
    )
}
// 提交
ConversationTracer.submitTurn(adaptLogToTurn(rawLog))
```
