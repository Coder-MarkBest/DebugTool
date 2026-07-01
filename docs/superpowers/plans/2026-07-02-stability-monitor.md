# Stability Monitor (debugtools-stability) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `debugtools-stability` 模块:系统应用从 DropBoxManager + 文件系统(`/data/anr/`, `/data/tombstones/`) 主动采集 Java/Native/ANR 崩溃,外部配置进程名列表,onAttach 扫一次 + 每 60s 定时 + 手动按钮,进程存活状态条 + 崩溃列表(时间在最前),独立 Tab"稳定性"。

**Architecture:** 纯逻辑下沉: `CrashCollector`(合并去重排序过滤) + `ProcessChecker`(遍历 /proc 检查存活)全部纯 JVM 可测;Android 粘合: `DropBoxSource`(DropBoxManager API) + `FileSystemSource`(读 `/data/anr/` `/data/tombstones/`) + `StabilityMonitor`(单例绑定数据源);`StabilityModule`(DebugModule+60s 定时器)+ 视图是展示层。

**Tech Stack:** Kotlin, Android(compileSdk 34, minSdk 26), JUnit4, Android Canvas。不引入 `org.json:json` — CrashEntry 不做 JSON 序列化（不持久化）。

**参考规格:** `docs/superpowers/specs/2026-07-02-stability-monitor-design.md`

---

## File Structure

新增模块 `debugtools-stability/`:
- `protocol/CrashType.kt`、`protocol/CrashEntry.kt`
- `scanner/CrashCollector.kt` — 纯逻辑:合并去重排序过滤
- `scanner/ProcessChecker.kt` — 纯逻辑:/proc 遍历
- `scanner/CrashSource.kt` — 数据源接口
- `scanner/DropBoxSource.kt` — DropBoxManager 实现
- `scanner/FileSystemSource.kt` — 文件系统扫描实现
- `StabilityMonitor.kt` — 单例(init/searchNow/processAliveStatus)
- `StabilityModule.kt` — DebugModule 入口(60s 定时器在此管理)
- `view/StabilityColors.kt`、`view/ProcessStatusBar.kt`、`view/CrashListView.kt`、`view/StabilityRootView.kt`
- `README.md`

测试:
- `CrashCollectorTest` — 合并去重/进程名过滤/时间排序/空输入
- `ProcessCheckerTest` — 模拟 /proc 目录

修改:
- `settings.gradle.kts` 加 `:debugtools-stability`
- `app/` demo 接入

---

## Task 1: 模块骨架(gradle + manifest + settings)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `debugtools-stability/build.gradle.kts`
- Create: `debugtools-stability/src/main/AndroidManifest.xml`

- [ ] **Step 1: settings.gradle.kts 加入模块**

在 `include(":debugtools-conversation")` 之后加:
```kotlin
include(":debugtools-stability")
```

- [ ] **Step 2: 创建 build.gradle.kts**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.stability"
    compileSdk = 34
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":debugtools-core"))
    testImplementation("junit:junit:4.13.2")
}
```

> 注意: 不引入 `org.json:json` — 本模块不做 JSON 持久化。

- [ ] **Step 3: 创建 AndroidManifest.xml**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: 验证构建**

Run: `./gradlew :debugtools-stability:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add settings.gradle.kts debugtools-stability/build.gradle.kts debugtools-stability/src/main/AndroidManifest.xml
git commit -m "chore(stability): scaffold debugtools-stability module"
```

---

## Task 2: 协议（CrashType + CrashEntry）

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/protocol/CrashType.kt`
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/protocol/CrashEntry.kt`

> 不做 JSON 序列化，不写测试 — 纯数据类，编译即可。

- [ ] **Step 1: CrashType.kt**

```kotlin
package com.debugtools.stability.protocol

enum class CrashType { JAVA_CRASH, NATIVE_CRASH, ANR }
```

- [ ] **Step 2: CrashEntry.kt**

```kotlin
package com.debugtools.stability.protocol

data class CrashEntry(
    val type: CrashType,
    val processName: String,
    val timestamp: Long,
    val sourcePath: String,
    val stackTrace: String,
    val pid: Int?
)
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :debugtools-stability:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability/protocol/
git commit -m "feat(stability): add CrashType and CrashEntry protocol"
```

---

## Task 3: CrashCollector（合并去重排序过滤，纯逻辑，TDD）

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/CrashCollector.kt`
- Test: `debugtools-stability/src/test/kotlin/com/debugtools/stability/scanner/CrashCollectorTest.kt`

- [ ] **Step 1: 写失败测试**

`CrashCollectorTest.kt`:
```kotlin
package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.protocol.CrashType
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashCollectorTest {

    private fun entry(type: CrashType, proc: String, ts: Long, src: String = "DropBox:test", trace: String = "stack-$ts") =
        CrashEntry(type, proc, ts, src, trace, null)

    // ── filter by process ──

    @Test fun `filterByProcess keeps only matching names`() {
        val entries = listOf(
            entry(CrashType.JAVA_CRASH, "com.a", 100L),
            entry(CrashType.NATIVE_CRASH, "com.b", 200L),
            entry(CrashType.ANR, "com.a", 300L)
        )
        val result = CrashCollector.filterByProcess(entries, listOf("com.a"))
        assertEquals(2, result.size)
        assertEquals(listOf("com.a", "com.a"), result.map { it.processName })
    }

    @Test fun `filterByProcess with empty names returns all`() {
        val entries = listOf(entry(CrashType.JAVA_CRASH, "com.a", 100L))
        assertEquals(1, CrashCollector.filterByProcess(entries, emptyList()).size)
    }

    @Test fun `filterByProcess with empty entries returns empty`() {
        assertEquals(0, CrashCollector.filterByProcess(emptyList(), listOf("com.a")).size)
    }

    // ── merge & dedup ──

    @Test fun `merge de-duplicates by timestamp + processName + trace prefix(200)`() {
        val trace = "Exception: boom\n".repeat(50) // long trace
        val e1 = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "DropBox:a", trace, null)
        val e2 = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "/data/anr/anr_1", trace, null) // same key -> dedup
        val e3 = CrashEntry(CrashType.JAVA_CRASH, "com.a", 200L, "DropBox:a", "different stack", null)
        val result = CrashCollector.merge(listOf(e1, e2), listOf(e3))
        assertEquals(2, result.size)
    }

    @Test fun `merge sorts by timestamp descending`() {
        val e1 = entry(CrashType.JAVA_CRASH, "com.a", 100L)
        val e2 = entry(CrashType.JAVA_CRASH, "com.a", 300L)
        val e3 = entry(CrashType.JAVA_CRASH, "com.a", 200L)
        val result = CrashCollector.merge(listOf(e1), listOf(e2, e3))
        assertEquals(listOf(300L, 200L, 100L), result.map { it.timestamp })
    }

    @Test fun `merge prefers DropBox source when duplicate`() {
        val trace = "same trace for dedup"
        val dropBox = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "DropBox:system_app_crash", trace, null)
        val fileSys = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "/data/anr/anr_1", trace, null)
        val result = CrashCollector.merge(listOf(dropBox), listOf(fileSys))
        assertEquals(1, result.size)
        assertEquals("DropBox:system_app_crash", result[0].sourcePath)
    }

    @Test fun `merge empty sources returns empty`() {
        assertEquals(0, CrashCollector.merge(emptyList(), emptyList()).size)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-stability:test`
Expected: FAIL（CrashCollector 未定义）。

- [ ] **Step 3: 实现 CrashCollector**

```kotlin
package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry

/** Pure functions over crash entries — merge, dedup, sort, filter. */
object CrashCollector {

    private const val DEDUP_TRACE_LEN = 200

    /**
     * Merge two source result lists into one de-duplicated, timestamp-descending list.
     * Dedup key: (timestamp, processName, stackTrace first [DEDUP_TRACE_LEN] chars).
     * When a duplicate exists, prefers the DropBox entry.
     */
    fun merge(dropBoxEntries: List<CrashEntry>, fileEntries: List<CrashEntry>): List<CrashEntry> {
        val seen = HashSet<String>()
        val result = mutableListOf<CrashEntry>()

        // Process DropBox first (preferred source)
        for (e in (dropBoxEntries + fileEntries).sortedByDescending { it.timestamp }) {
            val key = "${e.timestamp}|${e.processName}|${e.stackTrace.take(DEDUP_TRACE_LEN)}"
            if (seen.add(key)) result.add(e)
        }
        return result
    }

    /** Keep only entries whose [processName] is in [names]. If [names] is empty, return all. */
    fun filterByProcess(entries: List<CrashEntry>, names: List<String>): List<CrashEntry> {
        if (names.isEmpty()) return entries
        val nameSet = names.toSet()
        return entries.filter { it.processName in nameSet }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-stability:test`
Expected: PASS（7 个 collector 测试）。

- [ ] **Step 5: 提交**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/CrashCollector.kt \
  debugtools-stability/src/test/kotlin/com/debugtools/stability/scanner/CrashCollectorTest.kt
git commit -m "feat(stability): add pure CrashCollector (merge, dedup, sort, filter)"
```

---

## Task 4: ProcessChecker（/proc 遍历，纯逻辑，TDD）

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/ProcessChecker.kt`
- Test: `debugtools-stability/src/test/kotlin/com/debugtools/stability/scanner/ProcessCheckerTest.kt`

- [ ] **Step 1: 写失败测试**

`ProcessCheckerTest.kt`:
```kotlin
package com.debugtools.stability.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessCheckerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Write a fake /proc/<pid>/cmdline file with null-separated args. */
    private fun fakeProc(procDir: File, pid: Int, cmdline: String) {
        val dir = File(procDir, "$pid")
        dir.mkdirs()
        File(dir, "cmdline").writeBytes(cmdline.replace(" ", " ").toByteArray())
    }

    private fun checker(procDir: File) = ProcessChecker(procDir)

    @Test fun `check returns true for matching process`() {
        fakeProc(tmp.root, 123, "com.debugtools.sample")
        fakeProc(tmp.root, 456, "com.android.phone")
        val status = checker(tmp.root).check(listOf("com.debugtools.sample"))
        assertEquals(mapOf("com.debugtools.sample" to true), status)
    }

    @Test fun `check returns false for missing process`() {
        val status = checker(tmp.root).check(listOf("com.nonexistent"))
        assertEquals(mapOf("com.nonexistent" to false), status)
    }

    @Test fun `check handles processes with arguments`() {
        // cmdline: "com.xxx.voice --flag value" — first token is the process name
        fakeProc(tmp.root, 100, "com.xxx.voice --flag value")
        val status = checker(tmp.root).check(listOf("com.xxx.voice"))
        assertEquals(mapOf("com.xxx.voice" to true), status)
    }

    @Test fun `check returns multiple results`() {
        fakeProc(tmp.root, 1, "app.a")
        fakeProc(tmp.root, 2, "app.b")
        val status = checker(tmp.root).check(listOf("app.a", "app.b", "app.c"))
        assertEquals(
            mapOf("app.a" to true, "app.b" to true, "app.c" to false),
            status
        )
    }

    @Test fun `empty proc directory returns all false`() {
        val status = checker(tmp.root).check(listOf("com.a"))
        assertEquals(mapOf("com.a" to false), status)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-stability:test`
Expected: FAIL（ProcessChecker 未定义）。

- [ ] **Step 3: 实现 ProcessChecker**

```kotlin
package com.debugtools.stability.scanner

import java.io.File

/**
 * Checks whether named processes are alive by scanning /proc/*/cmdline.
 * [procDir] is injectable for testing (default = "/proc").
 */
class ProcessChecker(private val procDir: File = File("/proc")) {

    /**
     * Returns a map of each requested process name → alive status.
     * A process is considered alive if /proc/<pid>/cmdline starts with the name.
     */
    fun check(processNames: List<String>): Map<String, Boolean> {
        val alive = mutableSetOf<String>()
        val pidDirs = procDir.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } } ?: emptyArray()
        for (pidDir in pidDirs) {
            val cmdline = try {
                String(File(pidDir, "cmdline").readBytes())
                    .replace(' ', ' ')  // null-separated → space-separated
                    .trim()
            } catch (_: Exception) { continue }
            val name = cmdline.substringBefore(' ')
            if (name.isNotEmpty()) alive.add(name)
        }
        return processNames.associateWith { it in alive }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-stability:test`
Expected: PASS（5 个 checker 测试 + 7 个 collector 测试）。

- [ ] **Step 5: 提交**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/ProcessChecker.kt \
  debugtools-stability/src/test/kotlin/com/debugtools/stability/scanner/ProcessCheckerTest.kt
git commit -m "feat(stability): add ProcessChecker (/proc traversal)"
```

---

## Task 5: 数据源（CrashSource 接口 + DropBoxSource + FileSystemSource）

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/CrashSource.kt`
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/DropBoxSource.kt`
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/FileSystemSource.kt`

> Android 粘合层，无单测；编译验证。

- [ ] **Step 1: CrashSource.kt（接口）**

```kotlin
package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry

/** Abstraction over different crash data sources. */
interface CrashSource {
    /** Read all crash entries from this source. Non-null, returns empty list on failure. */
    fun readAll(): List<CrashEntry>
}
```

- [ ] **Step 2: DropBoxSource.kt**

```kotlin
package com.debugtools.stability.scanner

import android.os.DropBoxManager
import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.protocol.CrashType

/**
 * Reads crash entries from [DropBoxManager].
 * Tags queried: system_app_crash → JAVA_CRASH, system_app_anr → ANR,
 * SYSTEM_TOMBSTONE → NATIVE_CRASH, SYSTEM_NATIVE_CRASH → NATIVE_CRASH (API 31+).
 */
class DropBoxSource(private val dropBox: DropBoxManager) : CrashSource {

    private val tagMap = mapOf(
        "system_app_crash" to CrashType.JAVA_CRASH,
        "system_app_anr" to CrashType.ANR,
        "SYSTEM_TOMBSTONE" to CrashType.NATIVE_CRASH
    )

    override fun readAll(): List<CrashEntry> {
        val entries = mutableListOf<CrashEntry>()
        for ((tag, type) in tagMap) {
            var entry: DropBoxManager.Entry?
            var nextTime = 0L
            try {
                while (true) {
                    entry = dropBox.getNextEntry(tag, nextTime)
                    if (entry == null) break
                    nextTime = entry.timeMillis + 1
                    val text = entry.getText(8192) ?: continue
                    val parsed = parseDropBoxEntry(type, tag, entry, text) ?: continue
                    entries.add(parsed)
                }
            } catch (_: Exception) { /* tag may not exist; skip */ }
        }
        // Also try SYSTEM_NATIVE_CRASH if supported
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                var entry2: DropBoxManager.Entry?
                var nextTime2 = 0L
                while (true) {
                    entry2 = dropBox.getNextEntry("SYSTEM_NATIVE_CRASH", nextTime2)
                    if (entry2 == null) break
                    nextTime2 = entry2.timeMillis + 1
                    val text = entry2.getText(8192) ?: continue
                    val parsed = parseDropBoxEntry(CrashType.NATIVE_CRASH, "SYSTEM_NATIVE_CRASH", entry2, text)
                        ?: continue
                    entries.add(parsed)
                }
            }
        } catch (_: Exception) {}
        return entries
    }

    private fun parseDropBoxEntry(
        type: CrashType, tag: String, entry: DropBoxManager.Entry, text: String
    ): CrashEntry? {
        val procName = extractProcessName(text).ifEmpty { return null }
        return CrashEntry(
            type = type,
            processName = procName,
            timestamp = entry.timeMillis,
            sourcePath = "DropBox:$tag",
            stackTrace = text,
            pid = null
        )
    }

    private fun extractProcessName(text: String): String {
        // Common pattern: "Process: com.example.app" in crash headers
        val procMatch = Regex("Process:\\s*(\\S+)").find(text)
        return procMatch?.groupValues?.getOrNull(1) ?: ""
    }
}
```

- [ ] **Step 3: FileSystemSource.kt**

```kotlin
package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.protocol.CrashType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Scans well-known crash directories on the file system. */
class FileSystemSource : CrashSource {

    private data class DirMapping(val dir: String, val type: CrashType, val filePattern: Regex)

    private val dirs = listOf(
        DirMapping("/data/anr", CrashType.ANR, Regex("anr_|traces_")),
        DirMapping("/data/tombstones", CrashType.NATIVE_CRASH, Regex("tombstone_")),
        DirMapping("/data/system/dropbox", CrashType.JAVA_CRASH, Regex("system_app_crash|SYSTEM_CRASH"))
    )

    override fun readAll(): List<CrashEntry> {
        val entries = mutableListOf<CrashEntry>()
        for (mapping in dirs) {
            val dir = File(mapping.dir)
            if (!dir.canRead()) continue
            dir.listFiles()?.filter { it.isFile && mapping.filePattern.containsMatchIn(it.name) }?.forEach { file ->
                try {
                    val text = file.readText().take(16384) // cap at 16KB per file
                    val procName = extractProcessName(text).ifEmpty { continue }
                    entries.add(CrashEntry(
                        type = mapping.type,
                        processName = procName,
                        timestamp = file.lastModified(), // best we have without parsing
                        sourcePath = file.absolutePath,
                        stackTrace = text,
                        pid = extractPid(text)
                    ))
                } catch (_: Exception) {}
            }
        }
        return entries
    }

    private fun extractProcessName(text: String): String {
        val match = Regex("Process:\\s*(\\S+)").find(text) ?: return ""
        return match.groupValues[1]
    }

    private fun extractPid(text: String): Int? {
        val match = Regex("PID:\\s*(\\d+)").find(text) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
```

- [ ] **Step 4: 编译确认**

Run: `./gradlew :debugtools-stability:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/CrashSource.kt \
  debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/DropBoxSource.kt \
  debugtools-stability/src/main/kotlin/com/debugtools/stability/scanner/FileSystemSource.kt
git commit -m "feat(stability): add CrashSource interface with DropBox and FileSystem implementations"
```

---

## Task 6: StabilityMonitor（单例，Android 粘合）

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityMonitor.kt`

> 粘合层：绑定 DropBoxManager + ProcessChecker + CrashCollector。无单测；编译验证。

- [ ] **Step 1: 实现 StabilityMonitor**

```kotlin
package com.debugtools.stability

import android.content.Context
import android.os.DropBoxManager
import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.scanner.CrashCollector
import com.debugtools.stability.scanner.CrashSource
import com.debugtools.stability.scanner.DropBoxSource
import com.debugtools.stability.scanner.FileSystemSource
import com.debugtools.stability.scanner.ProcessChecker

/**
 * Process-wide entry point for stability monitoring.
 * System-app only — requires access to DropBoxManager and /data/* directories.
 *
 * ```kotlin
 * StabilityMonitor.init(context, listOf("com.xxx.voice", "com.xxx.asr"))
 * val entries = StabilityMonitor.searchNow()
 * val status  = StabilityMonitor.processAliveStatus()
 * ```
 */
object StabilityMonitor {

    private var processNames: List<String> = emptyList()
    private var dropBoxSource: CrashSource? = null
    private var fileSource: CrashSource? = null
    private var processChecker = ProcessChecker()

    /** Must be called first. Idempotent. */
    fun init(context: Context, names: List<String>) {
        if (processNames.isNotEmpty()) return
        processNames = names
        val dbm = context.applicationContext.getSystemService(Context.DROPBOX_SERVICE) as? DropBoxManager
        dropBoxSource = dbm?.let { DropBoxSource(it) }
        fileSource = FileSystemSource()
    }

    /** Manual full scan: reads all sources, merges, de-dups, filters, returns. */
    fun searchNow(): List<CrashEntry> {
        val drop = dropBoxSource?.readAll() ?: emptyList()
        val file = fileSource?.readAll() ?: emptyList()
        val merged = CrashCollector.merge(drop, file)
        return CrashCollector.filterByProcess(merged, processNames)
    }

    /** Returns alive status for all monitored process names. */
    fun processAliveStatus(): Map<String, Boolean> = processChecker.check(processNames)
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :debugtools-stability:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityMonitor.kt
git commit -m "feat(stability): add StabilityMonitor singleton (init, searchNow, processAliveStatus)"
```

---

## Task 7: 视图（配色 + 进程状态条 + 崩溃列表 + 容器）

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/view/StabilityColors.kt`
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/view/ProcessStatusBar.kt`
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/view/CrashListView.kt`
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/view/StabilityRootView.kt`

> Android 视图，纯绘制，无单测；编译验证。

- [ ] **Step 1: StabilityColors.kt**

```kotlin
package com.debugtools.stability.view

import com.debugtools.stability.protocol.CrashType

object StabilityColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val ACCENT = 0xFF2DD4BF.toInt()
    val ALIVE = 0xFF48BB78.toInt()
    val DEAD = 0xFFF43F5E.toInt()
    val JAVA_CRASH = 0xFFF43F5E.toInt()
    val NATIVE_CRASH = 0xFFF6AD55.toInt()
    val ANR = 0xFFFBBF24.toInt()

    fun crashColor(t: CrashType): Int = when (t) {
        CrashType.JAVA_CRASH -> JAVA_CRASH
        CrashType.NATIVE_CRASH -> NATIVE_CRASH
        CrashType.ANR -> ANR
    }

    fun crashEmoji(t: CrashType): String = when (t) {
        CrashType.JAVA_CRASH -> "💥"  // 💥
        CrashType.NATIVE_CRASH -> "🪦"  // 🪦
        CrashType.ANR -> "⏱️"           // ⏱️
    }
}
```

- [ ] **Step 2: ProcessStatusBar.kt（进程存活状态条）**

```kotlin
package com.debugtools.stability.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

@SuppressLint("ViewConstructor")
class ProcessStatusBar(
    context: Context,
    status: Map<String, Boolean>
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        if (status.isEmpty()) {
            addView(row("(未配置监控进程)", StabilityColors.TEXT_DIM))
        } else {
            status.forEach { (name, alive) ->
                val color = if (alive) StabilityColors.ALIVE else StabilityColors.DEAD
                val label = if (alive) "🟢 $name 正常" else "🔴 $name 异常"
                addView(row(label, color))
            }
        }
    }

    private fun row(text: String, color: Int) = TextView(context).apply {
        this.text = text; setTextColor(color); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
        setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
    }
}
```

- [ ] **Step 3: CrashListView.kt（崩溃列表）**

```kotlin
package com.debugtools.stability.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.stability.protocol.CrashEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("ViewConstructor")
class CrashListView(
    context: Context,
    entries: List<CrashEntry>
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    init {
        orientation = VERTICAL
        if (entries.isEmpty()) {
            addView(TextView(context).apply {
                text = "暂无崩溃记录"
                setTextColor(StabilityColors.TEXT_DIM); textSize = 12f
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            })
        }
        entries.forEach { e ->
            val row = TextView(context).apply {
                text = "${timeFormat.format(Date(e.timestamp))}  ${StabilityColors.crashEmoji(e.type)}  ${e.processName}  ${e.sourcePath}"
                setTextColor(StabilityColors.TEXT); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StabilityColors.SURFACE) }
                setOnClickListener {
                    // toggle expanded detail
                    if (tag == "expanded") {
                        text = "${timeFormat.format(Date(e.timestamp))}  ${StabilityColors.crashEmoji(e.type)}  ${e.processName}  ${e.sourcePath}"
                        tag = null
                    } else {
                        text = "${timeFormat.format(Date(e.timestamp))}  ${StabilityColors.crashEmoji(e.type)}  ${e.processName}\n${e.sourcePath}\n\n${e.stackTrace.take(2000)}"
                        tag = "expanded"
                    }
                }
            }
            addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (4 * density).toInt() })
        }
    }
}
```

- [ ] **Step 4: StabilityRootView.kt（容器）**

```kotlin
package com.debugtools.stability.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.stability.StabilityMonitor
import com.debugtools.stability.protocol.CrashEntry

/** Scrollable panel: process status bar → search button → crash list. */
@SuppressLint("ViewConstructor")
class StabilityRootView(context: Context) : ScrollView(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(12), p(12), p(12)) }
    private var entries: List<CrashEntry> = emptyList()

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(StabilityColors.BG)
        addView(content)
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refresh()
    }

    fun refresh() {
        val status = StabilityMonitor.processAliveStatus()
        entries = StabilityMonitor.searchNow()
        content.removeAllViews()

        // header
        content.addView(header("进程状态"))
        content.addView(ProcessStatusBar(context, status), lp())

        // search button
        content.addView(Button(context).apply {
            text = "🔍 立即搜索"
            setTextColor(StabilityColors.TEXT); textSize = 12f
            background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StabilityColors.ACCENT) }
            setOnClickListener { refresh() }
        }, lp())

        // crash list
        content.addView(header("崩溃记录"))
        content.addView(CrashListView(context, entries), lp())
    }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) }

    private fun header(t: String) = TextView(context).apply {
        text = t; setTextColor(StabilityColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }
}
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :debugtools-stability:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability/view/
git commit -m "feat(stability): add views (status bar, crash list, root container)"
```

---

## Task 8: StabilityModule（DebugModule 入口 + 60s 定时器）

**Files:**
- Create: `debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityModule.kt`

- [ ] **Step 1: 实现 StabilityModule**

```kotlin
package com.debugtools.stability

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.stability.view.StabilityRootView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StabilityModule : DebugModule {

    override val moduleId: String = "stability"
    override val tabTitle: String = "稳定性"

    private var rootView: StabilityRootView? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timerJob: Job? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        startTimer()
    }

    override fun onDetach() {
        timerJob?.cancel()
        scope.cancel()
        rootView = null
    }

    override fun createContentView(context: Context): View {
        rootView = StabilityRootView(context)
        return rootView!!
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun getBriefItems(): List<BriefItem> {
        val status = StabilityMonitor.processAliveStatus()
        val dead = status.count { !it.value }
        if (dead == 0) return listOf(BriefItem(text = "全部进程正常"))
        return listOf(BriefItem(text = "$dead 个进程异常"))
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(60_000L) // 60 seconds
                withContext(Dispatchers.Main) {
                    rootView?.refresh()
                }
            }
        }
    }
}
```

- [ ] **Step 2: 整模块构建 + 全量测试**

Run: `./gradlew :debugtools-stability:assembleDebug :debugtools-stability:test`
Expected: BUILD SUCCESSFUL；collector + checker 测试全过。

- [ ] **Step 3: 提交**

```bash
git add debugtools-stability/src/main/kotlin/com/debugtools/stability/StabilityModule.kt
git commit -m "feat(stability): add StabilityModule entry point with 60s timer"
```

---

## Task 9: 在 sample app 接入

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`

- [ ] **Step 1: app 依赖加入模块**

在 `app/build.gradle.kts` dependencies 块,`implementation(project(":debugtools-conversation"))` 之后加:
```kotlin
    implementation(project(":debugtools-stability"))
```

- [ ] **Step 2: 在 MainActivity 注册模块 + init**

**2a: 添加 import**

在 `import com.debugtools.conversation.ConversationMonitorModule` 附近加:
```kotlin
import com.debugtools.stability.StabilityModule
import com.debugtools.stability.StabilityMonitor
```

**2b: 注册模块** — 在 builder 链 `.register(ConversationMonitorModule())` 后加:
```kotlin
                .register(StabilityModule())
```

**2c: 初始化 StabilityMonitor** — 在 `ConversationTracer.init(applicationContext)` 后加:
```kotlin
            StabilityMonitor.init(applicationContext, listOf("com.debugtools.sample", "system_server"))
```

> 监控 `com.debugtools.sample`(demo 自己)和 `system_server`(系统进程,几乎永远存活,用来演示正常状态)。

- [ ] **Step 3: 构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/debugtools/sample/MainActivity.kt
git commit -m "feat(app): demo stability monitor with process list"
```

---

## Task 10: README

**Files:**
- Create: `debugtools-stability/README.md`

- [ ] **Step 1: 写 README**

```markdown
# debugtools-stability

系统级崩溃日志采集器:从 DropBoxManager + 文件系统(`/data/anr/`, `/data/tombstones/`) 主动采集 Java 崩溃、Native 崩溃(tombstone)、ANR trace,按配置的进程名列表过滤,定时检查 + 手动触发,时间倒序展示。

**前置条件:** 系统应用权限（能读 `/data/` 目录 + DropBoxManager）。

## 接入(2 步)

**1) Application.onCreate 尽早 init:**
```kotlin
StabilityMonitor.init(context, listOf("com.xxx.voice", "com.xxx.asr"))
```

**2) 注册模块:**
```kotlin
DebugTools.builder(context).register(StabilityModule()).build()
```

## 看什么

- **进程存活状态条**:绿色=正常,红色=进程挂了
- **「立即搜索」按钮**:手动触发一次全量扫描
- **崩溃列表**:每条格式 `时间  类型图标  进程名  来源路径`,点击展开堆栈
- **定时检查**:每 60 秒自动搜一次(进入 Tab 时启动,离开时停止)

## 数据来源

| 来源 | 方式 | 覆盖 |
|------|------|------|
| DropBoxManager | `getNextEntry()` 遍历标签 | `system_app_crash`, `system_app_anr`, `SYSTEM_TOMBSTONE`, `SYSTEM_NATIVE_CRASH` |
| `/data/anr/` | 目录扫描 | ANR traces |
| `/data/tombstones/` | 目录扫描 | Native 崩溃 |
| `/data/system/dropbox/` | 目录扫描(兜底) | Java 崩溃文件 |

两路数据取并集,按 `(时间, 进程名, 堆栈前200字符)` 去重,时间倒序。

## 约束

- 需系统应用权限。非系统应用会编译通过但运行时空数据。
- 不做持久化 —— 每次打开/定时/手动都是从系统源实时读取。
- 进程存活仅检查 `/proc` 存在,不做心跳/端口/响应检查。
- CrashEntry 会单次合并全部 DropBox 历史条目。数量大的设备上可考虑加上游分页。
```

- [ ] **Step 2: 提交**

```bash
git add debugtools-stability/README.md
git commit -m "docs(stability): add module README with integration guide"
```

---

## 验收清单

- [ ] `./gradlew :debugtools-stability:test` 全绿（CrashCollector 7 + ProcessChecker 5 = 12 测试）
- [ ] `:debugtools-stability:assembleDebug` 与 `:app:assembleDebug` 通过
- [ ] CrashType + CrashEntry 协议
- [ ] CrashCollector 合并去重排序过滤 纯逻辑可测
- [ ] ProcessChecker /proc 遍历 纯逻辑可测
- [ ] DropBoxSource + FileSystemSource 编译通过
- [ ] StabilityMonitor.init/searchNow/processAliveStatus
- [ ] 「稳定性」Tab:进程存活条 + 「立即搜索」按钮 + 崩溃列表(时间在前+展开)
- [ ] 定时器 60s onAttach 启动 / onDetach 停止
- [ ] demo 配置两个监控进程（自身 + system_server）
