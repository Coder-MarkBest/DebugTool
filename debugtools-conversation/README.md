# debugtools-conversation

对话链路追踪:接入方写适配层把现有对话日志映射到通用协议 → `submitTurn(turn)` 提交,SDK 记录、持久化(最近 50 次对话,App 私有目录卸载即删)、三层导航(会话→轮次→阶段时间线)+ 自动诊断。

## 设计理念

DebugTool 定义**通用对话轨迹协议**,接入方已有完整链路日志,仅需写一个**适配层**把原始日志字段映射到协议数据类(`ConversationTurn` / `TurnStage`)即可。DebugTool 对阶段语义零理解,只据协议字段做展示与诊断。

## 接入(3 步)

**1) Application.onCreate 尽早 init:**
```kotlin
ConversationTracer.init(context)
```

**2) 写适配层 + 提交整轮:**
```kotlin
// 适配层:你的原始日志 → ConversationTurn
fun adaptLogToTurn(raw: MyVoiceLog): ConversationTurn {
    return ConversationTurn(
        turnId = raw.traceId, turnIndex = raw.roundIndex,
        sessionId = raw.dialogId, startUptimeMs = raw.startTime,
        endUptimeMs = raw.endTime, userInput = raw.asrFinalText,
        stages = listOf(
            TurnStage("ASR", 0, 500, SUCCESS, "[audio]", raw.asrText, null, "asr"),
            TurnStage("NLU", 500, 550, SUCCESS, raw.asrText, raw.nluJson, null, "nlu"),
            // ...
        ),
        outcome = if (raw.error == null) TurnOutcome.SUCCESS else TurnOutcome.FAILED,
        tags = raw.domains
    )
}
// 提交
ConversationTracer.submitTurn(adaptLogToTurn(rawLog))
```

**3) 会话结束时标记(触发持久化):**
```kotlin
ConversationTracer.endSession(sessionId)
// 可选 startSession(id, metadata) 开头,不调也懒创建
```

注册模块:
```kotlin
DebugTools.builder(context).register(ConversationMonitorModule()).build()
```

## 看什么

- **L1 会话列表**:最近 50 次对话,每次:轮数、✓/✗、是否进行中。点进 →
- **L2 轮次列表**:该次所有轮次,每轮一行:#N、截断 userInput、色标 outcome、耗时。点进 →
- **L3 轮次详情**:阶段时间线(从左到右,绿/红/灰色块)+ 每阶段的 input/output/error + 诊断。

## 自动诊断

| 类型 | 含义 |
|------|------|
| 阶段失败 | 任意 stage FAILED |
| 慢阶段 | 耗时 > 500ms |
| 轮超时 | outcome == TIMEOUT |
| 轮中断 | outcome == ABORTED |
| 流水线间隙 | 前后阶段不连续,有空闲间隔 |

## 约束

- 仅同进程上报;数据存 `filesDir/conversation`,保留最近 50 次,**随 App 卸载删除**;不做网络上报。
- 阶段名不限枚举,接入方自定;Tool 只展示不校验。
- 整轮提交(`submitTurn`),不做流式逐阶段上报。
- 不做 confidence。
- 后续快照录制模块会聚合本模块数据。
