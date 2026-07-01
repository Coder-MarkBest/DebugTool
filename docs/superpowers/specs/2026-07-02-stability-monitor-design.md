# 设计：稳定性监控模块（debugtools-stability）

日期：2026-07-02
模块：新增 `debugtools-stability`（可选模块，依赖 `debugtools-core`）
状态：已批准设计，待写实现计划

## 1. 背景与目标

语音助手 App 运行在车机上，系统应用权限。当 App 或关联进程（ASR 引擎等）发生 Java 崩溃、Native 崩溃(tombstone)、ANR 时，测试/产品/项目人员无法第一时间发现和定位。DebugTool 作为系统级调试工具，能读取 DropBoxManager + `/data/anr/` + `/data/tombstones/` 等系统目录，主动采集崩溃记录，按配置的进程名列表过滤，定时检查 + 手动触发，时间倒序展示。

**职责划分：** DebugTool 定义崩溃数据结构（CrashType/CrashEntry），从系统数据源采集崩溃记录，按进程名过滤后展示。对崩溃语义零理解，只做采集、去重、排序、展示。

## 2. 范围

**做：**
- 协议（CrashType + CrashEntry）
- 双数据源采集（DropBoxManager API + 文件系统 `/data/anr/`, `/data/tombstones/`, `/data/system/dropbox/`）
- 进程存活检查（遍历 `/proc/*/cmdline` 匹配进程名）
- 监控引擎：onAttach 扫描一次 + 每 60s 定时检查 + 手动「立即搜索」按钮
- 崩溃列表：时间在最前 + 类型图标 + 进程名 + 来源路径，点击展开堆栈
- 进程存活状态条（绿正常/红异常）
- 外部配置进程名列表
- 独立 Tab"稳定性"

**不做（YAGNI）：**
- 不做网络上报
- 不解析堆栈语义（只原样展示）
- 不监控自定义 trace 日志（那是 logcat 的事）
- 不跨进程
- 不持久化崩溃记录（每次都是实时从系统源读取，不缓存）

## 3. 协议（数据契约）

```kotlin
enum class CrashType { JAVA_CRASH, NATIVE_CRASH, ANR }

data class CrashEntry(
    val type: CrashType,
    val processName: String,      // 匹配到的进程名
    val timestamp: Long,          // 崩溃时间（Wall Clock）
    val sourcePath: String,       // "DropBox:system_app_crash" 或 "/data/tombstones/tombstone_03"
    val stackTrace: String,       // 完整堆栈
    val pid: Int?                 // 进程 PID，未知则 null
)
```

**排序：** `listOf(entry)` 内已按 `timestamp` 降序排列（最近在前）。

## 4. 数据来源（系统应用权限）

### 4.1 DropBoxManager API

`android.os.DropBoxManager` — Android 内置的崩溃/ANR 暂存系统，所有应用崩溃默认写入。系统应用可直接读取。

查询标签：
- `system_app_crash` — Java 层崩溃
- `system_app_anr` — ANR
- `system_tombstone` / `SYSTEM_TOMBSTONE` — Native 崩溃
- `SYSTEM_NATIVE_CRASH` — Native 进程崩溃（API 31+）

通过 `getNextEntry(tag, lastTimestamp)` 遍历，解析 `Entry.getText()` 获取堆栈，过滤进程名。

### 4.2 文件系统扫描（兜底）

部分 OEM 不写 DropBox，直接读文件：

| 目录 | 内容 |
|------|------|
| `/data/anr/` | ANR traces（`traces_*.txt`、`anr_*`） |
| `/data/tombstones/` | Native 崩溃（`tombstone_*`） |
| `/data/system/dropbox/` | DropBox 持久化文件直接读 |

扫描逻辑：列出目录 → 读文件头识别进程名和时间 → 匹配过滤 → 取堆栈。

### 4.3 合并去重策略

两路数据取并集，去重依据：`(timestamp, processName, stackTrace 前 200 字符)`。同一崩溃两路都有时保留来源更具体的那条。

## 5. 进程存活检查

遍历 `/proc/*/cmdline` → 读取每个进程的命令行 → 匹配进程名列表。返回 `Map<String, Boolean>`。

仅检查进程是否存在即可（不检查响应、不检查端口）。

---

## 6. 监控引擎

`StabilityMonitor` 进程内单例。

```kotlin
object StabilityMonitor {
    /**
     * 初始化。必须最先调用。
     * @param context  用于拿 DropBoxManager + 文件系统
     * @param processNames 要监控的进程名列表
     */
    fun init(context: Context, processNames: List<String>)

    /** 手动触发一次全量搜索 */
    fun searchNow(): List<CrashEntry>

    /** 返回当前进程存活状态 */
    fun processAliveStatus(): Map<String, Boolean>
}
```

**触发时机：**

| 时机 | 行为 |
|------|------|
| 模块 `onAttach` | 调用 `searchNow()` + `processAliveStatus()`，更新视图 |
| 每 60 秒（定时器） | 调用 `processAliveStatus()` + `searchNow()`，增量刷新视图 |
| 按钮「立即搜索」 | 调用 `searchNow()`，立即刷新视图 |

**去重：** 每次 `searchNow()` 返回去重后的崩溃列表（`timestamp + processName + stackTrace 前 200 字符`），避免相同崩溃反复出现。

**纯逻辑下沉淀：**
- `CrashCollector.merge(sources): List<CrashEntry>` — 并集 + 去重 + 排序，纯 JVM 可测
- `CrashCollector.filterByProcess(entries, names): List<CrashEntry>` — 进程名过滤，纯 JVM 可测
- `ProcessChecker.check(names): Map<String, Boolean>` — 进程存活检查
- `StabilityScanner` — 粘合 `DropBoxManager` + 文件系统读取（Android 依赖）

## 7. 视图

`StabilityModule : DebugModule`，`tabTitle = "稳定性"`。

### 7.1 进程存活状态条（顶部固定）

```kotlin
class ProcessStatusBar(context, status: Map<String, Boolean>)
```

每个进程一行：`🟢 com.xxx.voice 正常` / `🔴 com.xxx.voice 异常`。颜色直接表达状态。

### 7.2 按钮「立即搜索」

点击 → `StabilityMonitor.searchNow()` → 刷新列表。

### 7.3 崩溃列表

```kotlin
class CrashListView(context, entries: List<CrashEntry>)
```

每行格式：`07-02 14:32:15  💥  com.xxx.voice  DropBox:system_app_crash`

**时间在最前**，然后是类型图标（💥 Java 崩溃 / 🪦 Native 崩溃 / ⏱ ANR）+ 进程名 + 来源路径。点击展开完整堆栈。空列表时显示"暂无崩溃记录"。

### 7.4 容器

```kotlin
class StabilityRootView : ScrollView
```

垂直排列：`ProcessStatusBar` → `Button("立即搜索")` → `CrashListView`。`onAttachedToWindow` 刷新所有数据。

### 7.5 配色

复用项目既有的深色卡片风格。存活状态绿 `#48BB78`、异常红 `#F43F5E`、类型图标色：Java 红 / Native 橙 / ANR 黄。

## 8. 定时器

在 `StabilityModule.onAttach` 时启动一个 `CoroutineScope` + `while(isActive) { delay(60_000); searchNow() }` 循环，`onDetach` 时取消。搜索在 `Dispatchers.IO` 执行，结果切回 Main 更新视图。

`searchNow()` 本身是幂等且线程安全的 — 每次调用都是从头扫描数据源，不依赖上次状态。

## 9. 组件 / 文件清单

```
debugtools-stability/
  protocol/CrashType.kt
  protocol/CrashEntry.kt
  scanner/ProcessChecker.kt         ← /proc/*/cmdline 遍历
  scanner/CrashSource.kt            ← 数据源抽象（DropBox / 文件系统）
  scanner/DropBoxSource.kt          ← DropBoxManager 实现
  scanner/FileSystemSource.kt       ← 文件系统扫描实现
  scanner/CrashCollector.kt         ← 合并去重排序，纯 JVM 可测
  StabilityMonitor.kt               ← 单例（init/searchNow/processAliveStatus + 去重缓存）
  StabilityModule.kt                ← DebugModule 入口（定时器在此管理）
  view/StabilityColors.kt
  view/ProcessStatusBar.kt
  view/CrashListView.kt
  view/StabilityRootView.kt
  README.md
```

测试：
- `CrashCollectorTest` — 合并去重 / 进程名过滤 / 时间排序 / 空输入
- `ProcessCheckerTest` — 模拟 `/proc` 目录结构

修改：
- `settings.gradle.kts` 加 `:debugtools-stability`
- `app/build.gradle.kts` 加依赖（demo 接入）

## 10. 已知约束 / 取舍

- 需要系统应用权限（`READ_LOGS` + 文件系统读取 `/data/` 目录）。非系统应用无法使用。
- 数据不做持久化 —— 每次打开 Tab 或定时/手动触发都是从系统源实时读取。
- DropBoxManager 的 `getNextEntry` 在 API 级别上有细微差异，`SYSTEM_NATIVE_CRASH` 需 API 31+。低版本回退到文件系统扫描。
- 进程存活检查仅检查进程是否存在于 `/proc`，不检查连接、响应或功能可用性。
- 定时器 60s 仅为扫描周期，不做心跳 ping。
