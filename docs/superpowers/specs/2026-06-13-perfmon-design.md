# 性能监控模块设计文档

> DebugTools SDK 新增模块 `debugtools-perfmon`，监控指定进程的 CPU、内存、线程使用情况，支持可视化趋势图。面向车机端**系统 App** 场景。

---

## 1. 概述

为 DebugTools SDK 增加一个新的可选模块 `debugtools-perfmon`，监控接入方指定的若干进程的性能指标（CPU/内存/线程），以双栏布局 + 折线趋势图展示。**只读浏览**，目标自身开销极低。

**典型使用场景：**
- 车机端跨进程排查"哪个进程突然吃 CPU"
- 找出某进程内"哪个线程在卡 UI 线程"
- 观察某次操作（如打开大模型对话）对主进程内存的瞬时影响
- 验证版本升级前后的性能基线

**关键前提：接入方是系统 App**，能跨进程读 `/proc/<pid>/`。

---

## 2. 范围与非范围

### V1 范围
| 能力 | 说明 |
|---|---|
| 进程级 CPU% / 内存 / 线程数 | 所有被监控进程，10s 采样 |
| 详情视图 | 选中进程的精确 PSS、Java 堆、Native 堆，Top 10 线程 CPU%，线程状态分布 |
| 时序折线图 | 最近 30 min（180 个数据点），CPU%、内存 |
| 告警视觉指示 | CPU > 80% 红，50–80% 橙；阈值可配置 |
| 进程重启容错 | pid 变化时按名字重新发现 |

### V1 非范围（后续）
- 历史数据持久化（App 关闭丢失）
- 多 App 跨界（仅监控接入方指定列表）
- FPS 监控 / 主线程卡顿检测（独立 feature）
- Trace / Profiler 联动（systrace、perfetto）
- 整机系统级 CPU/内存（只监控指定进程）
- 网络 I/O、磁盘 I/O 速率（独立 feature）

---

## 3. 模块拆分

```
debugtools-perfmon/                ← 新增模块
  ├── build.gradle.kts              ← 依赖 :debugtools-core
  └── src/main/kotlin/com/debugtools/perfmon/
      ├── PerfMonitorModule.kt      ← 实现 DebugModule，对外入口
      ├── Config.kt                 ← 阈值 + 窗口 + 采样间隔
      ├── data/
      │   ├── ProcessSample.kt      ← Tier 1 数据
      │   ├── ProcessDetail.kt      ← Tier 2 数据
      │   ├── ThreadInfo.kt
      │   ├── ThreadState.kt
      │   └── TimeSeries.kt         ← 环形缓冲，30min 窗口
      ├── source/                   ← 数据采集
      │   ├── ProcStatReader.kt     ← /proc/<pid>/stat 解析
      │   ├── ProcStatmReader.kt    ← /proc/<pid>/statm 解析
      │   ├── ThreadReader.kt       ← /proc/<pid>/task/ 遍历
      │   ├── MemInfoReader.kt      ← ActivityManager.getProcessMemoryInfo
      │   └── ProcDiscoverer.kt     ← 按名字→pid 发现 + 重启检测
      ├── sampler/
      │   ├── Tier1Sampler.kt       ← 所有进程，每 10s
      │   └── Tier2Sampler.kt       ← 仅当前选中进程，每 10s
      ├── repository/
      │   └── PerfRepository.kt     ← StateFlow 暴露给 UI
      ├── presenter/
      │   ├── PerfView.kt
      │   └── PerfPresenter.kt
      └── view/
          ├── PerfRootView.kt       ← 双栏布局
          ├── ProcessListView.kt    ← 左侧
          ├── ProcessDetailView.kt  ← 右侧
          └── widget/
              ├── LineChartView.kt  ← 折线图（Canvas 自绘）
              ├── ThreadBarView.kt  ← Top 10 线程进度条
              └── ThreadStateView.kt ← 横向堆叠状态条
```

接入方依赖：

```kotlin
// app/build.gradle.kts
debugImplementation("io.github.xxx:debugtools-perfmon:1.0")
```

---

## 4. 公开 API

### PerfMonitorModule 入口

```kotlin
class PerfMonitorModule private constructor(
    private val config: Config,
    private val targets: List<ProcessTarget>
) : DebugModule {

    override val moduleId = "debugtools_perfmon"
    override val tabTitle = "性能监控"

    override fun buildSettings(): List<SettingGroup> = ...
    override fun createContentView(context: Context): View = ...
    override fun getBriefItems(): List<BriefItem> = ...
    override fun onAttach(context: Context, storage: SettingsStorage) = ...
    override fun onDetach() = ...

    sealed class ProcessTarget {
        data class ByName(val processName: String) : ProcessTarget()
        data class ByPid(val pid: Int) : ProcessTarget()
    }

    class Builder {
        private val targets = mutableListOf<ProcessTarget>()
        private var config = Config()

        fun addProcessByName(processName: String) = apply {
            targets += ProcessTarget.ByName(processName)
        }
        fun addProcessByPid(pid: Int) = apply {
            targets += ProcessTarget.ByPid(pid)
        }
        fun updateIntervalSec(sec: Int) = apply {
            config = config.copy(updateIntervalSec = sec.coerceIn(5, 60))
        }
        fun windowMin(min: Int) = apply {
            config = config.copy(windowMin = min.coerceIn(5, 120))
        }
        fun cpuThresholdPercent(orange: Int = 50, red: Int = 80) = apply {
            config = config.copy(cpuOrangeThreshold = orange, cpuRedThreshold = red)
        }
        fun pssThresholdMb(red: Int) = apply {
            config = config.copy(pssRedThresholdMb = red)
        }
        fun build() = PerfMonitorModule(config, targets.toList())
    }

    companion object {
        fun builder() = Builder()
    }
}

data class Config(
    val updateIntervalSec: Int = 10,
    val windowMin: Int = 30,
    val cpuOrangeThreshold: Int = 50,
    val cpuRedThreshold: Int = 80,
    val pssRedThresholdMb: Int = 0  // 0 = 不告警
)
```

### 业务方接入示例

```kotlin
val perfmon = PerfMonitorModule.builder()
    .addProcessByName(packageName)                  // 主进程
    .addProcessByName("$packageName:asr")           // ASR 子进程
    .addProcessByName("$packageName:debug")         // :debug 子进程
    .updateIntervalSec(10)
    .windowMin(30)
    .cpuThresholdPercent(orange = 50, red = 80)
    .pssThresholdMb(500)
    .build()

DebugTools.builder(this)
    .register(perfmon)
    .build()
```

---

## 5. 采集架构（两层采样）

```
┌────────────────────────────────────────────────────────────────┐
│ Tier 1 Sampler  (所有目标进程, 每 10s, 极廉价)                  │
│                                                                │
│   foreach target in targets:                                   │
│     pid = ProcDiscoverer.resolve(target)                       │
│     if pid != null:                                            │
│       ProcStatReader.read(pid)    → CPU% (差分)                │
│       ProcStatmReader.read(pid)   → RSS                        │
│       ThreadReader.count(pid)     → 线程总数                    │
│     emit ProcessSample → Repository                            │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│ Tier 2 Sampler  (仅当前选中进程, 每 10s, 较贵)                  │
│                                                                │
│   selectedPid = current ProcessListView selection              │
│   if selectedPid != null:                                      │
│     MemInfoReader.read(selectedPid)                            │
│       → dalvikPss + nativePss + otherPss + totalPss            │
│     ThreadReader.detail(selectedPid)                           │
│       → List<ThreadInfo> (name, cpuPercent, state)             │
│     emit ProcessDetail → Repository                            │
└────────────────────────────────────────────────────────────────┘
```

**关键点：**
- 两个 sampler 是独立的协程，互不阻塞
- 用户没打开详情视图时，Tier 2 完全停止
- 切换选中进程时，Tier 2 自动跟着切（cancel + restart）
- 所有 I/O 在 `Dispatchers.IO`，不持锁
- Repository 的写操作 @Synchronized，UI 通过 StateFlow 拿快照

---

## 6. 数据源详解

### 6.1 进程 CPU% — `/proc/<pid>/stat`

格式（节选）：
```
1234 (main) S 1 1234 1234 0 -1 4194304 1234 0 0 0 utime stime cutime cstime ...
                                                  ↑    ↑
```

第 14 字段 `utime`（用户态 jiffies），第 15 字段 `stime`（内核态 jiffies）。

计算 CPU%：
```
1. 记录上次采样的 (utime + stime) = lastProcessTime
2. 同时记录 /proc/stat 第一行的总 CPU jiffies = lastTotalCpuTime
3. 本次采样：
     processDelta = currentProcessTime - lastProcessTime
     totalDelta = currentTotalCpuTime - lastTotalCpuTime
     cpuPercent = (processDelta / totalDelta) * 100 * 核心数
```

`_SC_CLK_TCK` 通常是 100，不需要在计算里出现（差分后约掉）。

### 6.2 RSS — `/proc/<pid>/statm`

格式：`size resident shared text lib data dt`

第二字段 `resident` 是 RSS（页数），乘以 `_SC_PAGESIZE`（4096 字节）得字节数。

**这是 Tier 1 用的"廉价内存"指标，约等于 PSS 但便宜。**

### 6.3 PSS / Java 堆 / Native 堆 — `ActivityManager.getProcessMemoryInfo`

```kotlin
val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
val info: Array<Debug.MemoryInfo> = am.getProcessMemoryInfo(intArrayOf(pid))
val mem = info[0]
val totalPssKb = mem.totalPss
val dalvikPssKb = mem.dalvikPss
val nativePssKb = mem.nativePss
val otherPssKb = mem.otherPss
```

**单次调用 50-200ms**（系统 App 无 throttle 限制），所以**只在 Tier 2 里用**。

### 6.4 线程列表 — `/proc/<pid>/task/<tid>/`

- 目录项数 = 线程数
- 每个 tid 目录下：
  - `comm` 文件：线程名（一行字符串）
  - `stat` 文件：第 3 字段是状态（R/S/D/Z/T），第 14/15 字段是 utime/stime
- 计算单线程 CPU% 同进程级（utime+stime 差分 / 总 CPU 差分）

### 6.5 进程发现与重启检测 — `ProcDiscoverer`

```kotlin
class ProcDiscoverer {
    fun resolve(target: ProcessTarget): Int? = when (target) {
        is ByPid -> if (isAlive(target.pid)) target.pid else null
        is ByName -> findPidByName(target.processName)
    }

    private fun findPidByName(name: String): Int? {
        // 遍历 /proc/*/cmdline，匹配名字
        // cmdline 第一个 null 分隔字段就是进程名
        File("/proc").listFiles()
            ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
            ?.firstOrNull { File(it, "cmdline").readBytes()
                .takeWhile { b -> b != 0.toByte() }
                .toByteArray()
                .let { bs -> String(bs) == name }
            }
            ?.name?.toInt()
    }

    private fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()
}
```

每次 Tier 1 采样都先 `resolve()`，pid 变化时自动跟上。

---

## 7. 数据模型

```kotlin
// Tier 1：所有进程的概要
data class ProcessSample(
    val target: PerfMonitorModule.ProcessTarget,
    val pid: Int?,                              // null = 进程不存在
    val timestamp: Long,
    val cpuPercent: Float,                      // 0–100*核心数（多核可能 >100）
    val rssBytes: Long,
    val threadCount: Int,
    val alive: Boolean
)

// Tier 2：选中进程的详情
data class ProcessDetail(
    val pid: Int,
    val timestamp: Long,
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val otherPssKb: Int,
    val threads: List<ThreadInfo>,              // Top 10 CPU%
    val threadStateDistribution: Map<ThreadState, Int>  // R/S/D/Z 计数
)

data class ThreadInfo(
    val tid: Int,
    val name: String,
    val cpuPercent: Float,
    val state: ThreadState
)

enum class ThreadState { RUNNING, SLEEPING, DISK_WAIT, ZOMBIE, STOPPED, UNKNOWN }

// 环形缓冲，按时间窗口存储 ProcessSample 或单个标量序列
class TimeSeries<T>(private val windowSec: Int, private val intervalSec: Int) {
    private val capacity = windowSec / intervalSec + 1
    private val buffer = ArrayDeque<TimedValue<T>>(capacity)

    @Synchronized fun add(timestamp: Long, value: T) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(TimedValue(timestamp, value))
    }
    @Synchronized fun snapshot(): List<TimedValue<T>> = buffer.toList()
}

data class TimedValue<T>(val timestamp: Long, val value: T)
```

---

## 8. 数据流

```
ProcDiscoverer  ProcStatReader  ProcStatmReader  ThreadReader  MemInfoReader
       │              │                │                │             │
       └──────────────┴────────────────┴────────────────┴─────────────┘
                                 │
                                 ▼
                  ┌──────────────────────────────┐
                  │ Tier1Sampler (Dispatchers.IO)│
                  │   loop every updateIntervalSec│
                  │   foreach target: emit       │
                  │     ProcessSample → repo     │
                  └──────────┬───────────────────┘
                             │
                  ┌──────────▼───────────────────┐
                  │ Tier2Sampler (Dispatchers.IO)│
                  │   active only when selected  │
                  │   emits ProcessDetail → repo │
                  └──────────┬───────────────────┘
                             │
                             ▼
                  ┌──────────────────────────────┐
                  │ PerfRepository               │
                  │   tier1Series : Map<target,  │
                  │     TimeSeries<ProcessSample>>│
                  │   tier2Detail : MutableStateFlow│
                  │     <ProcessDetail?>          │
                  └──────────┬───────────────────┘
                             │ StateFlow
                             ▼
                  ┌──────────────────────────────┐
                  │ PerfPresenter                │
                  │   sample(200ms)              │
                  │   buildViewModel             │
                  └──────────┬───────────────────┘
                             │ withContext(Main)
                             ▼
                  ┌──────────────────────────────┐
                  │ PerfRootView                 │
                  │   ProcessListView (左)        │
                  │   ProcessDetailView (右)      │
                  └──────────────────────────────┘

Tier 2 切换：
   ProcessListView.onItemSelected(pid)
        → Presenter.selectProcess(pid)
        → Tier2Sampler.restart(pid)
```

**线程模型：**
- 采样 IO：`Dispatchers.IO`
- Repository 写入：`@Synchronized`
- Presenter collect → Main：`withContext(Dispatchers.Main)`
- 图表更新：`postInvalidate()`

---

## 9. UI 设计（双栏布局）

```
┌────────────────────────┬───────────────────────────────────────────┐
│ 进程列表（左 30%）       │ 详情（右 70%）                              │
├────────────────────────┼───────────────────────────────────────────┤
│ ▶ ● main (1234)        │  当前进程：main (1234)                       │
│   CPU 23%  ▓▓▓░       │                                            │
│   RSS 145MB            │  ┌─ CPU% (最近 30min) ─────────────┐        │
│   线程 23              │  │                          瞬时 23%│        │
│                        │  │     ╱╲       ╱╲                │        │
│   ● :asr (1240)        │  │ ╱╲_╱  ╲_╱╲_╱  ╲___             │        │
│   CPU 5%               │  └────────────────────────────────┘        │
│   RSS 35MB             │                                            │
│   线程 7               │  ┌─ 内存 PSS/Java/Native ────────┐         │
│                        │  │  PSS  145MB  ━━━━━━━           │         │
│   ● :debug (1300)      │  │  Java  56MB  ━━━              │         │
│   CPU 1%               │  │  Native 32MB ━━               │         │
│   RSS 12MB             │  └───────────────────────────────┘         │
│   线程 4               │                                            │
│                        │  Top 10 线程 CPU%（当前）                   │
│   ✗ :stale (gone)      │   main           15.2%  ▓▓▓▓▓▓             │
│   进程已退出            │   asr-worker      4.1%  ▓                  │
│                        │   RenderThread    2.3%  ▓                  │
│                        │   GC               1.8%  ▓                  │
│                        │                                            │
│                        │  线程状态分布（23 总数）                     │
│                        │   R 运行: 3                                │
│                        │   S 睡眠: 18                               │
│                        │   D IO 等待: 2                              │
│                        │   Z 僵尸: 0                                │
└────────────────────────┴───────────────────────────────────────────┘
```

### UI 细节

- 左侧列表：每项点击切换详情，**当前选中加 ▶ 前缀**
- 进程已退出时：✗ 灰色显示，CPU/内存留空
- CPU 颜色：绿（< orange）/ 橙（orange–red）/ 红（> red）
- 折线图（`LineChartView`）：
  - X 轴：相对时间（"-5m", "-3m", "-1m", "now"）
  - Y 轴：自适应（CPU% 上限 = max(100, 实际峰值)，内存上限 = 实际峰值 × 1.2）
  - 多 series 不同色（CPU 一种 / PSS-Java-Native 三种）
  - 触摸不需要交互（车机端只看，不戳）
- 字号：列表行 14sp，详情数字 18sp（车机适配）
- 行高：≥ 48dp

### MINIMIZED / BRIEF 模式下

- BRIEF 简要条：`CPU 23% | PSS 145MB | 4P`（4 个进程）
- MINIMIZED：不渲染图表，但 Tier 1 采样保持（CPU 占用本来就 < 0.1%）
- 切换回 EXPANDED 时图表立即从已采集的环形缓冲恢复

---

## 10. 性能预算

| 场景 | 单次采样耗时 | CPU 占用（10s 周期下） |
|---|---|---|
| Tier 1：4 进程（典型） | < 10ms | < 0.1% |
| Tier 1：8 进程 | < 20ms | < 0.2% |
| Tier 2：选中进程（100 线程） | 50–250ms | < 2.5% |
| Tier 2：选中进程（1000 线程） | 100–500ms | < 5% |
| MINIMIZED 模式 | Tier 1 only | < 0.1% |
| Tier 2 detail 不打开 | Tier 1 only | < 0.1% |

**性能预算总目标：默认场景下 CPU 占用 < 0.5%，绝对不超过 5%。**

---

## 11. 错误处理

| 场景 | 处理 |
|---|---|
| 进程不存在 / 已退出 | `pid = null`, `alive = false`，列表显示 ✗，不再读它的 /proc |
| 进程重启（pid 变了） | `ProcDiscoverer.resolve()` 按名字重发现，下次采样自动恢复 |
| `/proc/<pid>/stat` 读失败（SELinux 拒绝） | catch IOException，标记进程为"权限不足"，UI 提示一次（不重复弹） |
| `getProcessMemoryInfo` 抛 SecurityException | 详情视图显示"内存信息不可用"，Tier 1 数据正常 |
| 单次采样耗时 > 周期 | 协程内 try-catch，跳过本次，下次照常；不堆积 |
| 线程数据采集中线程消失 | 单 tid 的文件读取 try-catch，跳过该线程 |
| Repository 写入时上限触发 | TimeSeries 是环形缓冲，自动驱逐最旧 |

---

## 12. 已知限制

1. **依赖系统 App 权限**：普通 App 在 Android 7+ 上读不到其他进程的 `/proc/<pid>/`，本模块对普通 App 无效（接入方需自检 ROM 是否允许）
2. **`getProcessMemoryInfo` 开销不可忽略**：仅在 Tier 2 用，且每个 pid 单次调用；不对所有进程批量调
3. **不持久化**：App 退出后历史数据丢失（V2 可加文件持久化）
4. **不支持系统级整机指标**（CPU 平均负载、free 内存等）：仅监控指定进程
5. **多核 CPU% 可能 > 100%**：进程在 2 核满载时显示 200%（这是真实占用，符合 top 等工具的展示）
6. **线程 Top N 在线程很多时漏掉一些**：默认 Top 10，可配置；漏掉的不在列表里但仍计入"线程状态分布"
