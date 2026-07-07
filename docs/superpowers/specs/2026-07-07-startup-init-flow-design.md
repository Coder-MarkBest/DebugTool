# 设计：应用初始化流程编排模块（debugtools-startup-init）

日期：2026-07-07
模块：新增 `startup-init-flow` 核心库 + 可选 `debugtools-startup-init` 桥接模块
状态：设计草案，待确认后写实现计划

## 1. 背景与目标

现有 `debugtools-startup` 已经能记录启动 step、依赖、耗时、失败、关键路径，并展示到“启动链路”Tab。但接入方仍需要手动在每个初始化点调用 `AppStartupMonitor.begin/success/fail/complete`。

真实 App 初始化通常不是简单线性流程：

- 有同步初始化，也有异步 callback / suspend 初始化。
- 有显式依赖关系，例如 NLU 依赖 config，TTS 依赖 ASR。
- 部分任务可以并发，部分任务失败后只影响依赖它的分支。
- 接入方希望“定义初始化流程后直接执行”，并自动进入启动链路。

目标是新增一个不依赖 DebugTools 的初始化编排核心库 `startup-init-flow`，提供一套通用初始化编排 API。接入方只描述初始化任务、依赖和执行方式；核心库负责调度、并发和错误处理。DebugTools 只通过可选桥接模块 `debugtools-startup-init` 订阅执行事件，并把事件写入 `AppStartupMonitor`。

## 2. 职责边界

`startup-init-flow` 负责：

- 表达初始化任务和依赖图。
- 校验依赖是否存在、是否有环。
- 按依赖关系调度任务。
- 支持同步、suspend、callback 三类初始化。
- 通过通用 `InitReporter` 输出执行事件。
- 在没有 reporter 或没有 DebugTools 的情况下仍能正常执行初始化流程。

`debugtools-startup-init` 负责：

- 提供 `StartupMonitorReporter`。
- 提供 `reportToStartupMonitor()` 扩展函数。
- 把核心库的 `taskStarted/taskSucceeded/taskFailed/flowCompleted` 映射到 `AppStartupMonitor.begin/success/fail/complete`。

`debugtools-startup` 继续负责：

- 记录 `StartupSession` / `StartupStep`。
- 持久化最近启动会话。
- 分析慢步骤、依赖倒挂、失败、关键路径。
- UI 展示和全局录制导出。

不做：

- 不替代业务自己的 DI 框架。
- 不自动接管 AndroidX Startup。
- 不跨进程调度初始化任务。
- 不做网络上报。
- 不做复杂重试、超时取消、优先级队列。第一版只做确定性依赖调度。

## 3. 模块结构

```text
startup-init-flow
  ├── StartupInitFlow.kt          // public builder / run entry
  ├── InitTask.kt                 // task 定义
  ├── InitTaskRunner.kt           // 同步 / suspend / callback 执行适配
  ├── InitGraph.kt                // 依赖校验、拓扑层、环检测
  ├── InitFlowRunner.kt           // 调度器
  └── InitReporter.kt             // 通用上报接口，默认 no-op

debugtools-startup-init
  ├── StartupMonitorReporter.kt   // 对接 AppStartupMonitor
  └── StartupInitFlowDebugToolsExt.kt // reportToStartupMonitor 扩展函数
```

Gradle 依赖：

```text
startup-init-flow -> Kotlin coroutines only
debugtools-startup-init -> startup-init-flow + debugtools-startup
```

`startup-init-flow` 是业务可直接使用的初始化编排库，不依赖 `debugtools-core`、`debugtools-startup` 或任何 DebugTools 模块。正式包如果不引入 DebugTools，只接入 `startup-init-flow` 也能正常完成应用初始化。

`debugtools-startup-init` 是可选调试桥接层。只有宿主希望把初始化流程接入“启动链路”Tab 时才需要引入。

## 4. Public API

推荐接入：

```kotlin
StartupInitFlow.builder()
    .task("config") {
        runBlockingInit {
            configSdk.init(context)
        }
    }
    .task("asr", dependsOn = listOf("config")) {
        suspendInit {
            asrSdk.initAsync()
        }
    }
    .task("nlu", dependsOn = listOf("config")) {
        callbackInit { done ->
            nluSdk.init { result ->
                done(result.exceptionOrNull())
            }
        }
    }
    .task("tts", dependsOn = listOf("asr")) {
        runBlockingInit {
            ttsSdk.init(context)
        }
    }
    .reporter(customReporter)
    .run()
```

如果引入了 DebugTools 桥接模块，可以额外使用：

```kotlin
StartupInitFlow.builder()
    .task("config") { runBlockingInit { configSdk.init(context) } }
    .reportToStartupMonitor()
    .run()
```

初始化入口：

```kotlin
object StartupInitFlow {
    fun builder(): Builder
}
```

Builder：

```kotlin
class Builder {
    fun task(
        name: String,
        dependsOn: List<String> = emptyList(),
        block: InitTaskBuilder.() -> Unit
    ): Builder

    fun reporter(reporter: InitReporter): Builder
    fun build(): InitFlowRunner
    suspend fun run(): InitFlowResult
}
```

`reportToStartupMonitor()` 不属于核心库 API。它由 `debugtools-startup-init` 作为扩展函数提供：

```kotlin
fun StartupInitFlow.Builder.reportToStartupMonitor(): StartupInitFlow.Builder
```

Task builder：

```kotlin
class InitTaskBuilder {
    fun runBlockingInit(block: () -> Unit)
    fun suspendInit(block: suspend () -> Unit)
    fun callbackInit(block: (done: (Throwable?) -> Unit) -> Unit)
}
```

结果模型：

```kotlin
data class InitFlowResult(
    val taskResults: List<InitTaskResult>,
    val success: Boolean
)

data class InitTaskResult(
    val name: String,
    val status: InitTaskStatus,
    val error: String? = null
)

enum class InitTaskStatus {
    SUCCESS,
    FAILED,
    SKIPPED
}
```

## 5. 调度语义

### 5.1 依赖图

- 每个 task name 在 flow 内唯一。
- `dependsOn` 中的每个 name 必须存在。
- 如果存在依赖环，flow 不启动任何业务任务。
- 校验失败会通过 reporter 输出 `flowFailed`。如果接入了 DebugTools 桥接层，桥接层会把它写入启动链路中的失败 step，便于 UI 里看到“启动编排配置错误”。

### 5.2 并发执行

调度器按依赖关系执行：

1. 找出所有依赖已成功完成的 ready tasks。
2. ready tasks 并发启动。
3. task 完成后解锁依赖它的后续 task。
4. 所有可执行 task 完成后结束 flow。

示例：

```text
config -> asr -> tts
       -> nlu
net
```

`config` 和 `net` 可并发；`asr/nlu` 等 `config` 成功后并发；`tts` 等 `asr` 成功后执行。

### 5.3 失败策略

第一版采用固定策略：

- task 自身抛异常或 callback 返回异常 -> `FAILED`。
- 依赖失败的 task 不执行，标记为 `SKIPPED`。
- 与失败分支无关的 task 继续执行。
- flow 结束时，如果存在 `FAILED` 或 `SKIPPED`，`InitFlowResult.success = false`。

`SKIPPED` 是核心库自己的任务结果，不要求 DebugTools 存在。对通用 reporter 的事件是 `taskSkipped(name, failedDependencies)`。

如果接入 DebugTools 桥接层，`SKIPPED` 不改变 `StartupStep` 的协议三态。对启动链路的记录方式是：

- 不调用被跳过 task 的 `begin`。
- 通过一个编排器诊断 step 记录跳过信息，名称为 `init_flow_skipped:<taskName>`，状态为 `FAILED`，错误信息包含失败依赖。

这样不需要扩展现有 `StepStatus`，也能在 UI 和报告里看到跳过原因。

## 6. Reporter 对接

Reporter 接口：

```kotlin
interface InitReporter {
    fun flowStarted(taskCount: Int)
    fun taskStarted(name: String, dependsOn: List<String>)
    fun taskSucceeded(name: String)
    fun taskFailed(name: String, error: Throwable)
    fun taskSkipped(name: String, failedDependencies: List<String>)
    fun flowFailed(reason: String)
    fun flowCompleted()
}
```

默认 `StartupMonitorReporter`：

```kotlin
class StartupMonitorReporter : InitReporter {
    override fun taskStarted(name: String, dependsOn: List<String>) {
        AppStartupMonitor.begin(name, dependsOn)
    }

    override fun taskSucceeded(name: String) {
        AppStartupMonitor.success(name)
    }

    override fun taskFailed(name: String, error: Throwable) {
        AppStartupMonitor.fail(name, error)
    }

    override fun flowCompleted() {
        AppStartupMonitor.complete()
    }
}
```

跳过和图校验失败使用合成 step 记录，例如：

```text
init_flow_skipped:tts
init_flow_invalid_graph
```

核心库默认 reporter 是 no-op：

```kotlin
object NoOpInitReporter : InitReporter { ... }
```

因此没有 DebugTools 时，`StartupInitFlow.builder().task(...).run()` 仍会执行所有初始化任务并返回 `InitFlowResult`。

## 7. 异步任务支持

### 同步任务

```kotlin
task("config") {
    runBlockingInit { configSdk.init(context) }
}
```

同步任务由调度器包装为 suspend 执行。是否切换 dispatcher 由调用方决定；第一版不内置 IO dispatcher，避免隐式线程策略。

### suspend 任务

```kotlin
task("asr") {
    suspendInit { asrSdk.initAsync() }
}
```

调度器直接调用 suspend block。

### callback 任务

```kotlin
task("nlu") {
    callbackInit { done ->
        nluSdk.init { result ->
            done(result.exceptionOrNull())
        }
    }
}
```

`callbackInit` 会挂起直到 `done` 被调用。重复调用 `done` 只认第一次。

## 8. 不接入 DebugTools 的使用方式

如果宿主不引入 DebugTools，只使用初始化编排：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()

        appScope.launch {
            val result = StartupInitFlow.builder()
                .task("config") { runBlockingInit { initConfig() } }
                .task("asr", dependsOn = listOf("config")) { suspendInit { initAsr() } }
                .task("tts", dependsOn = listOf("asr")) { suspendInit { initTts() } }
                .run()

            if (!result.success) {
                // 宿主自行决定是否降级、打业务日志或上报
            }
        }
    }
}
```

这种模式下：

- 没有悬浮窗。
- 没有 `AppStartupMonitor`。
- 没有 DebugTools 依赖。
- 初始化流程仍按依赖关系正常执行。
- 宿主可以通过 `InitFlowResult` 或自定义 `InitReporter` 获取结果。

## 9. 与现有启动模块的集成方式

如果宿主引入 DebugTools，希望初始化流程进入“启动链路”Tab，则额外接入 `debugtools-startup-init` 桥接模块，并尽早初始化 `AppStartupMonitor`：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStartupMonitor.init(this, appVersion = BuildConfig.VERSION_NAME)

        lifecycleScopeOrCustomScope.launch {
            StartupInitFlow.builder()
                .task("config") { runBlockingInit { initConfig() } }
                .task("asr", dependsOn = listOf("config")) { suspendInit { initAsr() } }
                .reportToStartupMonitor()
                .run()
        }
    }
}
```

悬浮窗仍注册现有启动链路模块：

```kotlin
DebugTools.builder(context)
    .register(StartupMonitorModule())
    .build()
```

接入 `debugtools-startup-init` 后，不需要业务在每个初始化点手写 `AppStartupMonitor.begin/success/fail`，但仍可以混用手写上报。

## 10. 测试策略

新增 JVM 单测：

- `InitGraphTest`
  - 拒绝重复 task name。
  - 检测缺失依赖。
  - 检测依赖环。
  - 计算可并发层级。
- `InitFlowRunnerTest`
  - 无依赖任务并发执行。
  - 依赖任务按顺序执行。
  - 失败分支的下游 task 被跳过。
  - 独立分支在某分支失败后继续执行。
  - callback task 等待 done。
  - callback task 重复 done 只认第一次。
- `StartupMonitorReporterTest`
  - task start/success/fail 映射到 `AppStartupMonitor`。
  - flow completed 调用 complete。
- `NoDebugToolsUsageTest`
  - 只依赖 `startup-init-flow` 时可以执行同步、suspend、callback 任务。
  - 不引用任何 `com.debugtools.*` 类型。

如 `AppStartupMonitor` 的全局单例不便测试 reporter，可先用 fake `InitReporter` 验证调度语义，`StartupMonitorReporter` 做轻量集成测试。

## 11. 文档与 Sample

接入文档需要新增“应用初始化流程编排”章节：

- 说明 `startup-init-flow` 可独立使用，不依赖 DebugTools。
- 说明什么时候额外接入 `debugtools-startup-init`。
- 给出同步、suspend、callback 三种示例。
- 说明依赖失败、跳过、并发执行的语义。
- 说明最终数据会进入现有“启动链路”Tab 和全局录制报告。

Sample App 可以把当前手写：

```kotlin
AppStartupMonitor.track("config") { ... }
AppStartupMonitor.track("asr", listOf("config")) { ... }
```

替换为：

```kotlin
StartupInitFlow.builder()
    .task("config") { runBlockingInit { ... } }
    .task("net") { runBlockingInit { ... } }
    .task("asr", dependsOn = listOf("config")) { runBlockingInit { ... } }
    .task("nlu", dependsOn = listOf("config")) { runBlockingInit { ... } }
    .task("tts", dependsOn = listOf("asr")) { runBlockingInit { ... } }
    .reportToStartupMonitor()
    .run()
```

这样 sample 展示的是接入方真实会使用的初始化编排方式。

## 12. 风险与取舍

- 第一版不做任务超时。超时策略牵涉取消、线程、业务 SDK 幂等，先不引入。
- 第一版不做重试。失败上报即可，重试由业务初始化逻辑自行实现。
- 第一版不自动选择 dispatcher。宿主明确在哪个协程上下文执行 flow。
- 使用合成失败 step 表达 `SKIPPED`，避免扩展现有 `StepStatus`。
- 混用手写 `AppStartupMonitor` 和编排器时，接入方需要避免 task name 冲突。
- 为满足“没有 DebugTools 也能正常使用”，核心库和 DebugTools 桥接层必须保持 Gradle module 隔离，核心库代码不得 import `com.debugtools.*`。

## 13. 成功标准

- 接入方能用一个 flow 描述同步、suspend、callback 初始化任务。
- 依赖关系能驱动并发和顺序执行。
- 失败分支不会阻塞独立分支。
- 不引入 DebugTools 时，初始化流程也能正常执行并返回结果。
- 引入 DebugTools 桥接层时，所有执行结果能进入现有启动链路 UI。
- 引入 DebugTools 桥接层时，全局录制报告能看到初始化任务及其失败/跳过信息。
- Sample App 使用新编排器生成启动链路示例。
