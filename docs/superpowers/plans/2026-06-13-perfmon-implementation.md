# 性能监控模块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `debugtools-perfmon` SDK module that monitors CPU, memory and threads for caller-specified processes via `/proc/<pid>/` + `ActivityManager`, with a car-friendly dual-column UI showing per-process trend charts and Top-N thread CPU.

**Architecture:** New optional Android library module depending on `:debugtools-core`. Two independent samplers feed an in-memory `PerfRepository` (ring-buffered time series, thread-safe via `@Synchronized`). Tier 1 reads cheap `/proc` files for all targets every 10s. Tier 2 reads expensive `ActivityManager.getProcessMemoryInfo` + per-thread data only for the currently-selected process. UI is a left list + right detail with Canvas-drawn line charts.

**Tech Stack:** Kotlin 1.9.22, Android API 26+, kotlinx-coroutines 1.7.3, JUnit 4, Robolectric 4.11.1, kotlinx-coroutines-test, system `/proc` filesystem, `ActivityManager.getProcessMemoryInfo`.

---

## File Structure

```
debugtools-perfmon/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   └── kotlin/com/debugtools/perfmon/
│       ├── PerfMonitorModule.kt        ← DebugModule entry + Builder
│       ├── Config.kt
│       ├── data/
│       │   ├── ProcessSample.kt        ← Tier 1 row
│       │   ├── ProcessDetail.kt        ← Tier 2 row
│       │   ├── ThreadInfo.kt
│       │   ├── ThreadState.kt
│       │   ├── ProcessTarget.kt        ← ByName / ByPid sealed class
│       │   └── TimeSeries.kt           ← ring buffer
│       ├── source/
│       │   ├── ProcStatReader.kt       ← /proc/<pid>/stat + /proc/stat
│       │   ├── ProcStatmReader.kt      ← /proc/<pid>/statm
│       │   ├── ThreadReader.kt         ← /proc/<pid>/task/
│       │   ├── MemInfoReader.kt        ← ActivityManager
│       │   └── ProcDiscoverer.kt       ← name → pid
│       ├── sampler/
│       │   ├── Tier1Sampler.kt
│       │   └── Tier2Sampler.kt
│       ├── repository/
│       │   └── PerfRepository.kt
│       ├── presenter/
│       │   ├── PerfView.kt             ← view interface
│       │   └── PerfPresenter.kt
│       └── view/
│           ├── PerfRootView.kt
│           ├── ProcessListView.kt
│           ├── ProcessDetailView.kt
│           └── widget/
│               ├── LineChartView.kt
│               ├── ThreadBarView.kt
│               └── ThreadStateView.kt
└── src/test/kotlin/com/debugtools/perfmon/
    ├── ConfigTest.kt
    ├── data/
    │   └── TimeSeriesTest.kt
    ├── source/
    │   ├── ProcStatReaderTest.kt
    │   ├── ProcStatmReaderTest.kt
    │   ├── ThreadReaderTest.kt
    │   ├── ProcDiscovererTest.kt
    │   └── MemInfoReaderTest.kt
    ├── repository/
    │   └── PerfRepositoryTest.kt
    ├── sampler/
    │   ├── Tier1SamplerTest.kt
    │   └── Tier2SamplerTest.kt
    ├── presenter/
    │   └── PerfPresenterTest.kt
    └── PerfMonitorModuleTest.kt
```

---

## Task 1: Gradle Module Setup

**Files:**
- Create: `debugtools-perfmon/build.gradle.kts`
- Create: `debugtools-perfmon/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Add module to settings.gradle.kts**

Open `/Users/xianxiaoli/ClaudeProjects/DebugTools/settings.gradle.kts` and add this line after the existing `include(":debugtools-okhttp-capture")`:

```kotlin
include(":debugtools-perfmon")
```

- [ ] **Step 2: Create `debugtools-perfmon/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.perfmon"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.annotation:annotation:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

- [ ] **Step 3: Create `debugtools-perfmon/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: Verify build**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add settings.gradle.kts debugtools-perfmon/ && git commit -m "chore(perfmon): add empty module with gradle setup"
```

---

## Task 2: Data Models

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/data/ProcessTarget.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/data/ThreadState.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/data/ThreadInfo.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/data/ProcessSample.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/data/ProcessDetail.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/data/TimeSeries.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/data/TimeSeriesTest.kt`

- [ ] **Step 1: Create `ProcessTarget.kt`**

```kotlin
package com.debugtools.perfmon.data

/** What the caller asked us to monitor. */
sealed class ProcessTarget {
    abstract val key: String  // stable identity for UI selection

    data class ByName(val processName: String) : ProcessTarget() {
        override val key: String get() = "name:$processName"
    }

    data class ByPid(val pid: Int) : ProcessTarget() {
        override val key: String get() = "pid:$pid"
    }
}
```

- [ ] **Step 2: Create `ThreadState.kt`**

```kotlin
package com.debugtools.perfmon.data

/** Process / thread state code from /proc/<pid>/stat field 3. */
enum class ThreadState(val code: Char) {
    RUNNING('R'),
    SLEEPING('S'),
    DISK_WAIT('D'),
    ZOMBIE('Z'),
    STOPPED('T'),
    UNKNOWN('?');

    companion object {
        fun fromCode(c: Char): ThreadState =
            values().firstOrNull { it.code == c } ?: UNKNOWN
    }
}
```

- [ ] **Step 3: Create `ThreadInfo.kt`**

```kotlin
package com.debugtools.perfmon.data

data class ThreadInfo(
    val tid: Int,
    val name: String,
    val cpuPercent: Float,
    val state: ThreadState
)
```

- [ ] **Step 4: Create `ProcessSample.kt`**

```kotlin
package com.debugtools.perfmon.data

/** Tier 1 row: cheap per-process snapshot taken every interval for all targets. */
data class ProcessSample(
    val target: ProcessTarget,
    val pid: Int?,              // null when process is gone
    val timestamp: Long,
    val cpuPercent: Float,      // 0..100*coreCount  (top-style)
    val rssBytes: Long,
    val threadCount: Int,
    val alive: Boolean
)
```

- [ ] **Step 5: Create `ProcessDetail.kt`**

```kotlin
package com.debugtools.perfmon.data

/** Tier 2 row: expensive detail snapshot, only for the user-selected process. */
data class ProcessDetail(
    val pid: Int,
    val timestamp: Long,
    val totalPssKb: Int,
    val dalvikPssKb: Int,
    val nativePssKb: Int,
    val otherPssKb: Int,
    val threads: List<ThreadInfo>,                       // Top N by CPU%
    val threadStateDistribution: Map<ThreadState, Int>   // all states, counts
)
```

- [ ] **Step 6: Write the failing test for `TimeSeries`**

```kotlin
// debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/data/TimeSeriesTest.kt
package com.debugtools.perfmon.data

import org.junit.Assert.*
import org.junit.Test

class TimeSeriesTest {

    @Test fun `add stores values in insertion order`() {
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        s.add(timestamp = 1L, value = 10)
        s.add(timestamp = 2L, value = 20)
        val snap = s.snapshot()
        assertEquals(2, snap.size)
        assertEquals(10, snap[0].value)
        assertEquals(20, snap[1].value)
    }

    @Test fun `evicts oldest when window is full`() {
        // windowSec=30, intervalSec=10 → capacity = 30/10 + 1 = 4
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        listOf(1, 2, 3, 4, 5).forEach { s.add(it.toLong(), it) }
        val snap = s.snapshot()
        assertEquals(4, snap.size)
        assertEquals(listOf(2, 3, 4, 5), snap.map { it.value })
    }

    @Test fun `snapshot returns immutable copy`() {
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        s.add(1L, 100)
        val snap = s.snapshot()
        s.add(2L, 200)
        assertEquals(1, snap.size)  // earlier snapshot unaffected
    }

    @Test fun `empty snapshot when no data`() {
        val s = TimeSeries<Int>(windowSec = 30, intervalSec = 10)
        assertTrue(s.snapshot().isEmpty())
    }
}
```

- [ ] **Step 7: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.TimeSeriesTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 8: Create `TimeSeries.kt`**

```kotlin
package com.debugtools.perfmon.data

/**
 * Thread-safe ring buffer keyed by wall-clock timestamp.
 * Capacity = windowSec / intervalSec + 1.
 * When full, [add] evicts the oldest entry.
 *
 * [snapshot] returns an independent immutable list so callers can iterate without
 * worrying about concurrent mutation.
 */
class TimeSeries<T>(private val windowSec: Int, private val intervalSec: Int) {
    private val capacity = windowSec / intervalSec + 1
    private val buffer = ArrayDeque<TimedValue<T>>(capacity)

    @Synchronized
    fun add(timestamp: Long, value: T) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(TimedValue(timestamp, value))
    }

    @Synchronized
    fun snapshot(): List<TimedValue<T>> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}

data class TimedValue<T>(val timestamp: Long, val value: T)
```

- [ ] **Step 9: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.TimeSeriesTest" 2>&1 | tail -10
```
Expected: 4 tests PASS.

- [ ] **Step 10: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/data/ debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/data/ && git commit -m "feat(perfmon): add data models (ProcessTarget, ThreadInfo, ProcessSample, ProcessDetail, TimeSeries)"
```

---

## Task 3: Config

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/Config.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/ConfigTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/ConfigTest.kt
package com.debugtools.perfmon

import org.junit.Assert.*
import org.junit.Test

class ConfigTest {

    @Test fun `default values match spec`() {
        val c = Config()
        assertEquals(10, c.updateIntervalSec)
        assertEquals(30, c.windowMin)
        assertEquals(50, c.cpuOrangeThreshold)
        assertEquals(80, c.cpuRedThreshold)
        assertEquals(0, c.pssRedThresholdMb)
        assertEquals(10, c.topThreadCount)
    }

    @Test fun `derived window in seconds`() {
        val c = Config(windowMin = 30, updateIntervalSec = 10)
        assertEquals(1800, c.windowSec)
    }

    @Test fun `custom values are stored`() {
        val c = Config(updateIntervalSec = 20, windowMin = 60, topThreadCount = 20)
        assertEquals(20, c.updateIntervalSec)
        assertEquals(60, c.windowMin)
        assertEquals(20, c.topThreadCount)
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ConfigTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `Config.kt`**

```kotlin
package com.debugtools.perfmon

/**
 * Tunable settings for the perf module. All values are upper bounds applied at sample
 * time; out-of-range values are clamped by the [PerfMonitorModule.Builder].
 */
data class Config(
    val updateIntervalSec: Int = 10,
    val windowMin: Int = 30,
    val cpuOrangeThreshold: Int = 50,
    val cpuRedThreshold: Int = 80,
    val pssRedThresholdMb: Int = 0,   // 0 = no memory alert
    val topThreadCount: Int = 10
) {
    val windowSec: Int get() = windowMin * 60
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ConfigTest" 2>&1 | tail -10
```
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/Config.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/ConfigTest.kt && git commit -m "feat(perfmon): add Config with default thresholds and window"
```

---

## Task 4: ProcStatReader (CPU%)

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ProcStatReader.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ProcStatReaderTest.kt`

Reads `/proc/<pid>/stat` (per-process CPU time) and `/proc/stat` (system-wide CPU time) and computes percent across samples via differential. Constructor takes a `procRoot: File` so tests can substitute a fake `/proc`.

- [ ] **Step 1: Write failing test**

```kotlin
// debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ProcStatReaderTest.kt
package com.debugtools.perfmon.source

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcStatReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Write a fake /proc/<pid>/stat line with the given utime+stime values. */
    private fun writePidStat(pid: Int, utime: Long, stime: Long) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        // pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt
        // majflt cmajflt utime stime ...
        // Fields 1..13 = $pid (comm) S 1 1 1 0 -1 0 0 0 0 0
        val fields = "$pid (test) S 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0"
        File(pidDir, "stat").writeText(fields)
    }

    /** Write a fake /proc/stat with given total CPU jiffies. */
    private fun writeProcStat(totalJiffies: Long) {
        // cpu user nice system idle iowait irq softirq steal guest guest_nice
        File(tmp.root, "stat").writeText("cpu  ${totalJiffies / 4} 0 ${totalJiffies / 4} ${totalJiffies / 4} ${totalJiffies / 4} 0 0 0 0 0\n")
    }

    @Test fun `first read returns null (no baseline yet)`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)
        writePidStat(pid = 100, utime = 50, stime = 50)
        writeProcStat(totalJiffies = 4000)
        assertNull(reader.read(100))
    }

    @Test fun `second read computes percent using delta`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)

        writePidStat(pid = 100, utime = 0, stime = 0)
        writeProcStat(totalJiffies = 4000)
        reader.read(100)  // primes baseline

        // 100 jiffies of process CPU vs 400 jiffies system delta on 4 cores
        // → fraction 0.25, * 100 * 4 = 100% (top-style: one full core)
        writePidStat(pid = 100, utime = 60, stime = 40)
        writeProcStat(totalJiffies = 4400)
        val pct = reader.read(100)
        assertNotNull(pct)
        assertEquals(100f, pct!!, 0.5f)
    }

    @Test fun `read returns null for missing pid`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)
        writeProcStat(totalJiffies = 4000)
        assertNull(reader.read(999))
    }

    @Test fun `reading 200 percent for two-core full load`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)
        writePidStat(100, 0, 0); writeProcStat(4000); reader.read(100)
        // 200 jiffies process delta, 400 system delta, 4 cores → 200%
        writePidStat(100, 100, 100); writeProcStat(4400)
        val pct = reader.read(100)
        assertEquals(200f, pct!!, 0.5f)
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ProcStatReaderTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `ProcStatReader.kt`**

```kotlin
package com.debugtools.perfmon.source

import java.io.File

/**
 * Computes per-process top-style CPU% (0..100*coreCount) by reading
 * /proc/<pid>/stat (utime+stime) and /proc/stat (total system jiffies) and
 * taking the delta against the previous successful read.
 *
 * Returns null until a second sample is available, or when the pid is missing.
 *
 * Not thread-safe — caller is expected to call from a single coroutine.
 *
 * [procRoot] is normally `File("/proc")` but can be substituted in tests with a
 * TemporaryFolder containing fake `<pid>/stat` files and a top-level `stat` file.
 */
class ProcStatReader(
    private val procRoot: File,
    private val coreCount: Int = Runtime.getRuntime().availableProcessors()
) {
    private data class Baseline(val processJiffies: Long, val totalJiffies: Long)

    private val baselines = mutableMapOf<Int, Baseline>()

    fun read(pid: Int): Float? {
        val processJiffies = readProcessJiffies(pid) ?: return null
        val totalJiffies = readTotalJiffies() ?: return null

        val prev = baselines[pid]
        baselines[pid] = Baseline(processJiffies, totalJiffies)
        if (prev == null) return null

        val processDelta = processJiffies - prev.processJiffies
        val totalDelta = totalJiffies - prev.totalJiffies
        if (totalDelta <= 0L) return null

        val fraction = processDelta.toFloat() / totalDelta.toFloat()
        return fraction * 100f * coreCount
    }

    /** Drops the baseline so this pid is re-baselined on next read (e.g. after restart). */
    fun forget(pid: Int) {
        baselines.remove(pid)
    }

    private fun readProcessJiffies(pid: Int): Long? = try {
        val statFile = File(procRoot, "$pid/stat")
        if (!statFile.exists()) return null
        // Parse field 14 (utime) + field 15 (stime). The comm field may contain
        // spaces, so find the closing paren and split from there.
        val raw = statFile.readText()
        val rparen = raw.lastIndexOf(')')
        if (rparen < 0) return null
        // After ')', fields are: state(3) ppid(4) pgrp(5) session(6) tty_nr(7)
        // tpgid(8) flags(9) minflt(10) cminflt(11) majflt(12) cmajflt(13) utime(14) stime(15)
        val rest = raw.substring(rparen + 2).trim().split(Regex("\\s+"))
        val utime = rest[11].toLong()
        val stime = rest[12].toLong()
        utime + stime
    } catch (_: Exception) {
        null
    }

    private fun readTotalJiffies(): Long? = try {
        val statFile = File(procRoot, "stat")
        if (!statFile.exists()) return null
        // first line: cpu  user nice system idle iowait irq softirq steal guest guest_nice
        val firstLine = statFile.bufferedReader().use { it.readLine() } ?: return null
        firstLine.trim().split(Regex("\\s+"))
            .drop(1)         // drop "cpu"
            .take(10)        // take all numeric fields
            .sumOf { it.toLong() }
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ProcStatReaderTest" 2>&1 | tail -10
```
Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ProcStatReader.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ProcStatReaderTest.kt && git commit -m "feat(perfmon): add ProcStatReader for differential CPU% from /proc"
```

---

## Task 5: ProcStatmReader (RSS)

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ProcStatmReader.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ProcStatmReaderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.perfmon.source

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcStatmReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writePidStatm(pid: Int, sizePages: Long, residentPages: Long) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        File(pidDir, "statm").writeText("$sizePages $residentPages 0 0 0 0 0\n")
    }

    @Test fun `reads resident pages and converts to bytes`() {
        val reader = ProcStatmReader(tmp.root, pageSize = 4096)
        writePidStatm(pid = 100, sizePages = 1000, residentPages = 256)
        val bytes = reader.readRssBytes(100)
        assertEquals(256L * 4096, bytes)
    }

    @Test fun `returns null for missing pid`() {
        val reader = ProcStatmReader(tmp.root, pageSize = 4096)
        assertNull(reader.readRssBytes(999))
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ProcStatmReaderTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `ProcStatmReader.kt`**

```kotlin
package com.debugtools.perfmon.source

import java.io.File

/**
 * Reads /proc/<pid>/statm to compute RSS (resident set size) in bytes.
 * Format: size resident shared text lib data dt  (all in page units)
 */
class ProcStatmReader(
    private val procRoot: File,
    private val pageSize: Int = 4096
) {
    fun readRssBytes(pid: Int): Long? = try {
        val statmFile = File(procRoot, "$pid/statm")
        if (!statmFile.exists()) return null
        val parts = statmFile.readText().trim().split(Regex("\\s+"))
        val residentPages = parts[1].toLong()
        residentPages * pageSize
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ProcStatmReaderTest" 2>&1 | tail -10
```
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ProcStatmReader.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ProcStatmReaderTest.kt && git commit -m "feat(perfmon): add ProcStatmReader for RSS from /proc/<pid>/statm"
```

---

## Task 6: ProcDiscoverer (name → pid)

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ProcDiscoverer.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ProcDiscovererTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ProcessTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcDiscovererTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeProcess(pid: Int, cmdline: String) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        // /proc/<pid>/cmdline uses null-byte separators; first segment is the process name.
        val bytes = cmdline.toByteArray() + 0x00.toByte()
        File(pidDir, "cmdline").writeBytes(bytes)
    }

    @Test fun `ByPid returns same pid when process exists`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.example")
        assertEquals(100, disco.resolve(ProcessTarget.ByPid(100)))
    }

    @Test fun `ByPid returns null when process gone`() {
        val disco = ProcDiscoverer(tmp.root)
        assertNull(disco.resolve(ProcessTarget.ByPid(999)))
    }

    @Test fun `ByName finds pid by matching first cmdline segment`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.other")
        writeProcess(pid = 200, cmdline = "com.example.target")
        writeProcess(pid = 300, cmdline = "another")
        assertEquals(200, disco.resolve(ProcessTarget.ByName("com.example.target")))
    }

    @Test fun `ByName returns null when no match`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.other")
        assertNull(disco.resolve(ProcessTarget.ByName("com.missing")))
    }

    @Test fun `ByName picks up new pid after restart`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.example")
        assertEquals(100, disco.resolve(ProcessTarget.ByName("com.example")))
        // Simulate restart: old pid gone, new pid
        File(tmp.root, "100").deleteRecursively()
        writeProcess(pid = 250, cmdline = "com.example")
        assertEquals(250, disco.resolve(ProcessTarget.ByName("com.example")))
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ProcDiscovererTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `ProcDiscoverer.kt`**

```kotlin
package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ProcessTarget
import java.io.File

/**
 * Resolves a [ProcessTarget] to a live pid by scanning [procRoot] for numeric
 * directories and matching the first segment of each `cmdline` (null-separated).
 *
 * ByName resolution picks the first matching pid (typically the only one for
 * application processes). Re-running resolve picks up a new pid if the process
 * was restarted with a different pid.
 */
class ProcDiscoverer(private val procRoot: File) {

    fun resolve(target: ProcessTarget): Int? = when (target) {
        is ProcessTarget.ByPid -> if (isAlive(target.pid)) target.pid else null
        is ProcessTarget.ByName -> findPidByName(target.processName)
    }

    private fun isAlive(pid: Int): Boolean = File(procRoot, pid.toString()).exists()

    private fun findPidByName(name: String): Int? {
        val children = procRoot.listFiles() ?: return null
        for (dir in children) {
            if (!dir.isDirectory) continue
            val pid = dir.name.toIntOrNull() ?: continue
            val cmdlineFile = File(dir, "cmdline")
            if (!cmdlineFile.exists()) continue
            try {
                val bytes = cmdlineFile.readBytes()
                val end = bytes.indexOf(0x00.toByte()).let { if (it < 0) bytes.size else it }
                val firstSeg = String(bytes, 0, end)
                if (firstSeg == name) return pid
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ProcDiscovererTest" 2>&1 | tail -10
```
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ProcDiscoverer.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ProcDiscovererTest.kt && git commit -m "feat(perfmon): add ProcDiscoverer for name-to-pid resolution and restart detection"
```

---

## Task 7: ThreadReader (thread enumeration + per-thread CPU)

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ThreadReader.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ThreadReaderTest.kt`

Reads `/proc/<pid>/task/` for thread enumeration, `/proc/<pid>/task/<tid>/{comm,stat}` for per-thread name + state + CPU jiffies. Per-thread CPU% uses the same differential approach as `ProcStatReader` but keyed by (pid, tid).

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ThreadState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThreadReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeProcStat(totalJiffies: Long) {
        File(tmp.root, "stat").writeText(
            "cpu  ${totalJiffies / 4} 0 ${totalJiffies / 4} ${totalJiffies / 4} ${totalJiffies / 4} 0 0 0 0 0\n"
        )
    }

    private fun writeThread(pid: Int, tid: Int, name: String, state: Char, utime: Long, stime: Long) {
        val taskDir = File(tmp.root, "$pid/task/$tid").apply { mkdirs() }
        File(taskDir, "comm").writeText("$name\n")
        File(taskDir, "stat").writeText("$tid ($name) $state 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0")
    }

    @Test fun `countThreads counts task dir entries`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeThread(100, 100, "main", 'R', 0, 0)
        writeThread(100, 101, "worker", 'S', 0, 0)
        writeThread(100, 102, "gc", 'S', 0, 0)
        assertEquals(3, r.countThreads(100))
    }

    @Test fun `countThreads returns 0 for missing pid`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        assertEquals(0, r.countThreads(999))
    }

    @Test fun `readDetailed first sample primes baseline`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 50, 50)
        val list = r.readDetailed(100)
        assertEquals(1, list.size)
        assertEquals("main", list[0].name)
        assertEquals(0f, list[0].cpuPercent, 0.01f)
        assertEquals(ThreadState.RUNNING, list[0].state)
    }

    @Test fun `readDetailed second sample computes percent via delta`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 0, 0)
        r.readDetailed(100)
        // Second sample
        writeProcStat(4400)
        writeThread(100, 100, "main", 'R', 60, 40)
        val list = r.readDetailed(100)
        assertEquals(100f, list[0].cpuPercent, 0.5f)
    }

    @Test fun `state code maps to ThreadState enum`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeProcStat(4000)
        writeThread(100, 100, "a", 'R', 0, 0)
        writeThread(100, 101, "b", 'S', 0, 0)
        writeThread(100, 102, "c", 'D', 0, 0)
        writeThread(100, 103, "d", 'Z', 0, 0)
        val list = r.readDetailed(100)
        val byName = list.associateBy { it.name }
        assertEquals(ThreadState.RUNNING, byName["a"]!!.state)
        assertEquals(ThreadState.SLEEPING, byName["b"]!!.state)
        assertEquals(ThreadState.DISK_WAIT, byName["c"]!!.state)
        assertEquals(ThreadState.ZOMBIE, byName["d"]!!.state)
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ThreadReaderTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `ThreadReader.kt`**

```kotlin
package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ThreadInfo
import com.debugtools.perfmon.data.ThreadState
import java.io.File

/**
 * Enumerates threads of a process under /proc/<pid>/task/ and computes per-thread
 * top-style CPU% (0..100*coreCount) via differential against the previous successful read.
 *
 * Not thread-safe — call from a single coroutine.
 */
class ThreadReader(
    private val procRoot: File,
    private val coreCount: Int = Runtime.getRuntime().availableProcessors()
) {
    private data class TidBaseline(val tidJiffies: Long, val totalJiffies: Long)
    private val baselines = mutableMapOf<Pair<Int, Int>, TidBaseline>()

    /** Cheap path: just the number of task entries. */
    fun countThreads(pid: Int): Int {
        val taskDir = File(procRoot, "$pid/task")
        return taskDir.listFiles { f -> f.isDirectory }?.size ?: 0
    }

    /** Expensive path: enumerate threads with name, state, CPU%. */
    fun readDetailed(pid: Int): List<ThreadInfo> {
        val taskDir = File(procRoot, "$pid/task")
        val tidDirs = taskDir.listFiles { f -> f.isDirectory } ?: return emptyList()
        val totalJiffies = readTotalJiffies() ?: return emptyList()

        val results = ArrayList<ThreadInfo>(tidDirs.size)
        for (dir in tidDirs) {
            val tid = dir.name.toIntOrNull() ?: continue
            val stat = parseThreadStat(File(dir, "stat")) ?: continue
            val cpuPct = computeCpuPercent(pid, tid, stat.utime + stat.stime, totalJiffies)
            results += ThreadInfo(
                tid = tid,
                name = stat.comm,
                cpuPercent = cpuPct,
                state = stat.state
            )
        }
        return results
    }

    fun forget(pid: Int) {
        baselines.keys.removeAll { it.first == pid }
    }

    private data class ThreadStat(val comm: String, val state: ThreadState, val utime: Long, val stime: Long)

    private fun parseThreadStat(statFile: File): ThreadStat? = try {
        if (!statFile.exists()) return null
        val raw = statFile.readText()
        val rparen = raw.lastIndexOf(')')
        if (rparen < 0) return null
        val comm = raw.substring(raw.indexOf('(') + 1, rparen)
        val rest = raw.substring(rparen + 2).trim().split(Regex("\\s+"))
        val state = ThreadState.fromCode(rest[0].firstOrNull() ?: '?')
        val utime = rest[11].toLong()
        val stime = rest[12].toLong()
        ThreadStat(comm, state, utime, stime)
    } catch (_: Exception) {
        null
    }

    private fun readTotalJiffies(): Long? = try {
        val statFile = File(procRoot, "stat")
        if (!statFile.exists()) return null
        val firstLine = statFile.bufferedReader().use { it.readLine() } ?: return null
        firstLine.trim().split(Regex("\\s+"))
            .drop(1).take(10).sumOf { it.toLong() }
    } catch (_: Exception) {
        null
    }

    private fun computeCpuPercent(pid: Int, tid: Int, tidJiffies: Long, totalJiffies: Long): Float {
        val key = pid to tid
        val prev = baselines[key]
        baselines[key] = TidBaseline(tidJiffies, totalJiffies)
        if (prev == null) return 0f
        val tidDelta = tidJiffies - prev.tidJiffies
        val totalDelta = totalJiffies - prev.totalJiffies
        if (totalDelta <= 0L) return 0f
        return (tidDelta.toFloat() / totalDelta.toFloat()) * 100f * coreCount
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.ThreadReaderTest" 2>&1 | tail -10
```
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/ThreadReader.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/ThreadReaderTest.kt && git commit -m "feat(perfmon): add ThreadReader for enumeration and per-thread CPU%"
```

---

## Task 8: MemInfoReader (PSS via ActivityManager)

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/MemInfoReader.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/MemInfoReaderTest.kt`

Wraps `ActivityManager.getProcessMemoryInfo` and returns a simple data object.

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.perfmon.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemInfoReaderTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun `read returns null for nonexistent pid`() {
        val reader = MemInfoReader(context)
        // Pid 99999 almost certainly doesn't exist in Robolectric environment;
        // getProcessMemoryInfo returns empty/zero values which we map to null.
        val result = reader.read(99999)
        // Either null or zero — both indicate "no real data"
        assertTrue(result == null || result.totalPssKb == 0)
    }

    @Test fun `read returns own process info for myPid`() {
        val reader = MemInfoReader(context)
        val result = reader.read(android.os.Process.myPid())
        assertNotNull(result)
        // In Robolectric, getProcessMemoryInfo may return zeros — we only check
        // the call didn't throw.
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.MemInfoReaderTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `MemInfoReader.kt`**

```kotlin
package com.debugtools.perfmon.source

import android.app.ActivityManager
import android.content.Context

/**
 * Wraps [ActivityManager.getProcessMemoryInfo] to fetch precise PSS / Java heap /
 * Native heap for a single pid. Returns null when the call throws or returns no info
 * (process gone, permission denied, etc).
 *
 * Single-shot call is 50–200ms for typical apps; only use from Tier 2 sampler.
 */
class MemInfoReader(context: Context) {
    private val am = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    data class Memory(
        val totalPssKb: Int,
        val dalvikPssKb: Int,
        val nativePssKb: Int,
        val otherPssKb: Int
    )

    fun read(pid: Int): Memory? = try {
        val info = am.getProcessMemoryInfo(intArrayOf(pid))
        if (info.isEmpty()) null else {
            val m = info[0]
            Memory(
                totalPssKb = m.totalPss,
                dalvikPssKb = m.dalvikPss,
                nativePssKb = m.nativePss,
                otherPssKb = m.otherPss
            )
        }
    } catch (_: Exception) {
        null
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.MemInfoReaderTest" 2>&1 | tail -10
```
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/source/MemInfoReader.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/source/MemInfoReaderTest.kt && git commit -m "feat(perfmon): add MemInfoReader wrapping ActivityManager.getProcessMemoryInfo"
```

---

## Task 9: PerfRepository

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/repository/PerfRepository.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/repository/PerfRepositoryTest.kt`

Holds `Map<targetKey, TimeSeries<ProcessSample>>` for Tier 1 + a single `MutableStateFlow<ProcessDetail?>` for Tier 2. Exposes a combined `state: StateFlow<Snapshot>`.

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.perfmon.repository

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import org.junit.Assert.*
import org.junit.Test

class PerfRepositoryTest {

    private fun sample(target: ProcessTarget, ts: Long, cpu: Float = 0f, alive: Boolean = true) =
        ProcessSample(
            target = target, pid = if (alive) 100 else null, timestamp = ts,
            cpuPercent = cpu, rssBytes = 0L, threadCount = 0, alive = alive
        )

    @Test fun `addSample appends to that target's series`() {
        val repo = PerfRepository(Config())
        val t = ProcessTarget.ByName("a")
        repo.addSample(sample(t, ts = 1L))
        repo.addSample(sample(t, ts = 2L))
        val snap = repo.state.value.series[t.key]!!.snapshot()
        assertEquals(2, snap.size)
    }

    @Test fun `series is independent per target`() {
        val repo = PerfRepository(Config())
        val a = ProcessTarget.ByName("a")
        val b = ProcessTarget.ByName("b")
        repo.addSample(sample(a, ts = 1L, cpu = 10f))
        repo.addSample(sample(b, ts = 1L, cpu = 99f))
        assertEquals(10f, repo.state.value.series[a.key]!!.snapshot().last().value.cpuPercent)
        assertEquals(99f, repo.state.value.series[b.key]!!.snapshot().last().value.cpuPercent)
    }

    @Test fun `setDetail and clearDetail update detail flow`() {
        val repo = PerfRepository(Config())
        assertNull(repo.state.value.detail)
        repo.setDetail(
            com.debugtools.perfmon.data.ProcessDetail(
                pid = 100, timestamp = 1L,
                totalPssKb = 1, dalvikPssKb = 1, nativePssKb = 1, otherPssKb = 1,
                threads = emptyList(), threadStateDistribution = emptyMap()
            )
        )
        assertEquals(100, repo.state.value.detail!!.pid)
        repo.clearDetail()
        assertNull(repo.state.value.detail)
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.PerfRepositoryTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `PerfRepository.kt`**

```kotlin
package com.debugtools.perfmon.repository

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimeSeries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory storage for all per-process time series plus the (single) selected
 * process detail. Exposes a [Snapshot] StateFlow consumed by the UI.
 *
 * Per-target series share the same windowSec / intervalSec from [Config], so each
 * series holds at most windowSec/intervalSec + 1 samples (e.g. 180 for 30min/10s).
 */
class PerfRepository(private val config: Config) {

    /** Immutable snapshot for UI. The TimeSeries instances inside are also internally
     *  immutable views; UI only reads via [TimeSeries.snapshot]. */
    data class Snapshot(
        val series: Map<String, TimeSeries<ProcessSample>>,
        val detail: ProcessDetail?
    )

    private val seriesByTargetKey = LinkedHashMap<String, TimeSeries<ProcessSample>>()
    private val _state = MutableStateFlow(Snapshot(series = emptyMap(), detail = null))
    val state: StateFlow<Snapshot> = _state
    private var detail: ProcessDetail? = null

    @Synchronized
    fun addSample(sample: ProcessSample) {
        val key = sample.target.key
        val series = seriesByTargetKey.getOrPut(key) {
            TimeSeries(windowSec = config.windowSec, intervalSec = config.updateIntervalSec)
        }
        series.add(sample.timestamp, sample)
        publish()
    }

    @Synchronized
    fun setDetail(d: ProcessDetail) {
        detail = d
        publish()
    }

    @Synchronized
    fun clearDetail() {
        detail = null
        publish()
    }

    private fun publish() {
        _state.value = Snapshot(
            series = seriesByTargetKey.toMap(),
            detail = detail
        )
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.PerfRepositoryTest" 2>&1 | tail -10
```
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/repository/PerfRepository.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/repository/PerfRepositoryTest.kt && git commit -m "feat(perfmon): add PerfRepository for per-target time series + selected detail"
```

---

## Task 10: Tier1Sampler

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/sampler/Tier1Sampler.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/sampler/Tier1SamplerTest.kt`

Coroutine-based loop that combines `ProcDiscoverer` + `ProcStatReader` + `ProcStatmReader` + `ThreadReader.countThreads` to emit `ProcessSample` for each target every `updateIntervalSec`.

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.perfmon.sampler

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.ProcDiscoverer
import com.debugtools.perfmon.source.ProcStatReader
import com.debugtools.perfmon.source.ProcStatmReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class Tier1SamplerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeProcStat(total: Long) {
        File(tmp.root, "stat").writeText(
            "cpu  ${total / 4} 0 ${total / 4} ${total / 4} ${total / 4} 0 0 0 0 0\n"
        )
    }

    private fun writeProcess(pid: Int, name: String, utime: Long, stime: Long, rssPages: Long) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        File(pidDir, "cmdline").writeBytes(name.toByteArray() + 0x00.toByte())
        File(pidDir, "stat").writeText("$pid ($name) S 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0")
        File(pidDir, "statm").writeText("1000 $rssPages 0 0 0 0 0\n")
        File(pidDir, "task/$pid").mkdirs()  // one thread
    }

    @Test fun `samples each target every interval and emits to repository`() = runTest {
        writeProcStat(4000)
        writeProcess(100, "com.example", 0, 0, 256)

        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier1Sampler(
            targets = listOf(ProcessTarget.ByName("com.example")),
            repository = repo,
            config = Config(updateIntervalSec = 10),
            discoverer = ProcDiscoverer(tmp.root),
            statReader = ProcStatReader(tmp.root, coreCount = 4),
            statmReader = ProcStatmReader(tmp.root, pageSize = 4096),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )

        sampler.start(this)
        advanceTimeBy(50L)  // first sample on start

        // Update fake /proc and advance one interval
        writeProcStat(4400)
        writeProcess(100, "com.example", 60, 40, 256)
        advanceTimeBy(10_001L)

        val snap = repo.state.value.series["name:com.example"]!!.snapshot()
        assertTrue("expected at least 2 samples, got ${snap.size}", snap.size >= 2)
        // Last sample should have CPU% ~100 (one full core)
        val last = snap.last().value
        assertEquals(100f, last.cpuPercent, 1f)
        assertEquals(256L * 4096, last.rssBytes)
        assertTrue(last.alive)

        sampler.stop()
    }

    @Test fun `samples missing process as alive=false`() = runTest {
        writeProcStat(4000)
        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier1Sampler(
            targets = listOf(ProcessTarget.ByName("com.absent")),
            repository = repo,
            config = Config(updateIntervalSec = 10),
            discoverer = ProcDiscoverer(tmp.root),
            statReader = ProcStatReader(tmp.root, coreCount = 4),
            statmReader = ProcStatmReader(tmp.root, pageSize = 4096),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )
        sampler.start(this)
        advanceTimeBy(50L)
        val snap = repo.state.value.series["name:com.absent"]!!.snapshot()
        assertEquals(1, snap.size)
        assertFalse(snap[0].value.alive)
        assertNull(snap[0].value.pid)
        sampler.stop()
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.Tier1SamplerTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `Tier1Sampler.kt`**

```kotlin
package com.debugtools.perfmon.sampler

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.ProcDiscoverer
import com.debugtools.perfmon.source.ProcStatReader
import com.debugtools.perfmon.source.ProcStatmReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loops every [Config.updateIntervalSec] seconds and emits one [ProcessSample]
 * per target into the [PerfRepository]. Designed to be extremely cheap — each
 * iteration is a few /proc reads (< 10ms for typical 4-target workloads).
 */
class Tier1Sampler(
    private val targets: List<ProcessTarget>,
    private val repository: PerfRepository,
    private val config: Config,
    private val discoverer: ProcDiscoverer,
    private val statReader: ProcStatReader,
    private val statmReader: ProcStatmReader,
    private val threadReader: ThreadReader
) {
    private var job: Job? = null
    private val pidByTargetKey = HashMap<String, Int?>()

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                sampleOnce()
                delay(config.updateIntervalSec * 1_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sampleOnce() {
        val now = System.currentTimeMillis()
        for (target in targets) {
            val newPid = discoverer.resolve(target)
            val oldPid = pidByTargetKey[target.key]
            if (oldPid != null && oldPid != newPid) {
                // pid changed — drop baselines so next read primes fresh
                statReader.forget(oldPid)
                threadReader.forget(oldPid)
            }
            pidByTargetKey[target.key] = newPid

            val sample = if (newPid == null) {
                ProcessSample(
                    target = target, pid = null, timestamp = now,
                    cpuPercent = 0f, rssBytes = 0L, threadCount = 0, alive = false
                )
            } else {
                val cpu = statReader.read(newPid) ?: 0f
                val rss = statmReader.readRssBytes(newPid) ?: 0L
                val threads = threadReader.countThreads(newPid)
                ProcessSample(
                    target = target, pid = newPid, timestamp = now,
                    cpuPercent = cpu, rssBytes = rss, threadCount = threads, alive = true
                )
            }
            repository.addSample(sample)
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.Tier1SamplerTest" 2>&1 | tail -10
```
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/sampler/Tier1Sampler.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/sampler/Tier1SamplerTest.kt && git commit -m "feat(perfmon): add Tier1Sampler for cheap per-process polling"
```

---

## Task 11: Tier2Sampler

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/sampler/Tier2Sampler.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/sampler/Tier2SamplerTest.kt`

Only runs when a target pid is selected. Combines `MemInfoReader` + `ThreadReader.readDetailed` to emit `ProcessDetail`.

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.perfmon.sampler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.MemInfoReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Tier2SamplerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun writeProcStat(total: Long) {
        File(tmp.root, "stat").writeText(
            "cpu  ${total / 4} 0 ${total / 4} ${total / 4} ${total / 4} 0 0 0 0 0\n"
        )
    }

    private fun writeThread(pid: Int, tid: Int, name: String, state: Char, utime: Long, stime: Long) {
        val taskDir = File(tmp.root, "$pid/task/$tid").apply { mkdirs() }
        File(taskDir, "comm").writeText("$name\n")
        File(taskDir, "stat").writeText("$tid ($name) $state 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0")
    }

    @Test fun `selectPid emits detail on next interval`() = runTest {
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 0, 0)
        writeThread(100, 101, "worker", 'S', 0, 0)

        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier2Sampler(
            repository = repo,
            config = Config(updateIntervalSec = 10),
            memReader = MemInfoReader(context),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )
        sampler.start(this)
        sampler.selectPid(100)
        advanceTimeBy(50L)

        val d = repo.state.value.detail
        assertNotNull("expected detail after selectPid", d)
        assertEquals(100, d!!.pid)
        assertEquals(2, d.threads.size)

        sampler.stop()
    }

    @Test fun `selectPid null clears detail`() = runTest {
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 0, 0)
        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier2Sampler(
            repository = repo,
            config = Config(updateIntervalSec = 10),
            memReader = MemInfoReader(context),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )
        sampler.start(this)
        sampler.selectPid(100)
        advanceTimeBy(50L)
        assertNotNull(repo.state.value.detail)

        sampler.selectPid(null)
        advanceTimeBy(50L)
        assertNull(repo.state.value.detail)
        sampler.stop()
    }
}
```

- [ ] **Step 2: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.Tier2SamplerTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 3: Create `Tier2Sampler.kt`**

```kotlin
package com.debugtools.perfmon.sampler

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ThreadState
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.MemInfoReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the currently-selected pid every interval and emits a [ProcessDetail].
 * When [selectPid] is called with a different pid, restarts the loop targeting
 * the new pid. Null pid pauses sampling and clears the detail in the repository.
 */
class Tier2Sampler(
    private val repository: PerfRepository,
    private val config: Config,
    private val memReader: MemInfoReader,
    private val threadReader: ThreadReader
) {
    private var loopJob: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile private var selectedPid: Int? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        scope = null
    }

    fun selectPid(pid: Int?) {
        if (pid == selectedPid) return
        selectedPid = pid
        loopJob?.cancel()
        if (pid == null) {
            repository.clearDetail()
            return
        }
        loopJob = scope?.launch {
            while (isActive && selectedPid == pid) {
                sampleOnce(pid)
                delay(config.updateIntervalSec * 1_000L)
            }
        }
    }

    private fun sampleOnce(pid: Int) {
        val mem = memReader.read(pid)
        val threads = threadReader.readDetailed(pid)
        val top = threads.sortedByDescending { it.cpuPercent }.take(config.topThreadCount)
        val distribution: Map<ThreadState, Int> = threads.groupingBy { it.state }.eachCount()
        repository.setDetail(
            ProcessDetail(
                pid = pid,
                timestamp = System.currentTimeMillis(),
                totalPssKb = mem?.totalPssKb ?: 0,
                dalvikPssKb = mem?.dalvikPssKb ?: 0,
                nativePssKb = mem?.nativePssKb ?: 0,
                otherPssKb = mem?.otherPssKb ?: 0,
                threads = top,
                threadStateDistribution = distribution
            )
        )
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.Tier2SamplerTest" 2>&1 | tail -10
```
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/sampler/Tier2Sampler.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/sampler/Tier2SamplerTest.kt && git commit -m "feat(perfmon): add Tier2Sampler for selected-process detail polling"
```

---

## Task 12: PerfPresenter + PerfView Interface

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/presenter/PerfView.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/presenter/PerfPresenter.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/presenter/PerfPresenterTest.kt`

The presenter:
- Collects `repository.state` (throttled with `sample(200ms)` like okhttp-capture)
- Drives the Tier 2 sampler via `selectPid(pid)`
- Pushes ViewModel-shaped data to `PerfView`

- [ ] **Step 1: Create `PerfView.kt`**

```kotlin
package com.debugtools.perfmon.presenter

import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimedValue

interface PerfView {
    fun showList(rows: List<ProcessRow>)
    fun showDetail(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>)
}

/** Per-row state for the left-side list. */
data class ProcessRow(
    val targetKey: String,
    val displayName: String,    // process name or pid
    val pid: Int?,
    val cpuPercent: Float,
    val rssBytes: Long,
    val threadCount: Int,
    val alive: Boolean,
    val selected: Boolean
)
```

- [ ] **Step 2: Write failing test for presenter**

```kotlin
package com.debugtools.perfmon.presenter

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.data.TimedValue
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.repository.PerfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerfPresenterTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeView : PerfView {
        var listUpdates = 0
        var lastRows: List<ProcessRow> = emptyList()
        var lastDetail: ProcessDetail? = null
        var lastSeries: List<TimedValue<ProcessSample>> = emptyList()
        override fun showList(rows: List<ProcessRow>) { listUpdates++; lastRows = rows }
        override fun showDetail(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>) {
            lastDetail = detail; lastSeries = cpuSeries
        }
    }

    private fun sample(target: ProcessTarget, cpu: Float = 0f, pid: Int? = 100) =
        ProcessSample(
            target = target, pid = pid, timestamp = 1L,
            cpuPercent = cpu, rssBytes = 0L, threadCount = 0, alive = pid != null
        )

    @Test fun `showList builds rows from latest sample per target`() = runTest(dispatcher) {
        val repo = PerfRepository(Config())
        val view = FakeView()
        var selectedPid: Int? = null
        val presenter = PerfPresenter(
            repository = repo,
            scope = this,
            sampleMs = 0L,
            onSelectPid = { selectedPid = it }
        )
        presenter.attachView(view)
        repo.addSample(sample(ProcessTarget.ByName("a"), cpu = 12f))
        repo.addSample(sample(ProcessTarget.ByName("b"), cpu = 99f, pid = null))
        advanceTimeBy(50L)
        assertEquals(2, view.lastRows.size)
        val byName = view.lastRows.associateBy { it.displayName }
        assertEquals(12f, byName["a"]!!.cpuPercent, 0.01f)
        assertFalse(byName["b"]!!.alive)
        presenter.detach()
    }

    @Test fun `selectTarget calls onSelectPid and marks row selected`() = runTest(dispatcher) {
        val repo = PerfRepository(Config())
        val view = FakeView()
        var selectedPid: Int? = null
        val presenter = PerfPresenter(repo, this, sampleMs = 0L, onSelectPid = { selectedPid = it })
        presenter.attachView(view)
        repo.addSample(sample(ProcessTarget.ByName("a"), cpu = 12f, pid = 100))
        advanceTimeBy(50L)
        presenter.selectTarget("name:a")
        advanceTimeBy(50L)
        assertEquals(100, selectedPid)
        assertTrue(view.lastRows.single { it.targetKey == "name:a" }.selected)
        presenter.detach()
    }

    @Test fun `detach stops emissions`() = runTest(dispatcher) {
        val repo = PerfRepository(Config())
        val view = FakeView()
        val presenter = PerfPresenter(repo, this, sampleMs = 0L, onSelectPid = {})
        presenter.attachView(view)
        repo.addSample(sample(ProcessTarget.ByName("a")))
        advanceTimeBy(50L)
        val before = view.listUpdates
        presenter.detach()
        repo.addSample(sample(ProcessTarget.ByName("b")))
        advanceTimeBy(50L)
        assertEquals(before, view.listUpdates)
    }
}
```

- [ ] **Step 3: Create `PerfPresenter.kt`**

```kotlin
package com.debugtools.perfmon.presenter

import com.debugtools.perfmon.repository.PerfRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

class PerfPresenter(
    private val repository: PerfRepository,
    private val scope: CoroutineScope,
    private val sampleMs: Long = 200L,
    private val onSelectPid: (Int?) -> Unit
) {
    private var view: PerfView? = null
    private var job: Job? = null
    private val selectedTargetKey = MutableStateFlow<String?>(null)

    fun attachView(view: PerfView) {
        this.view = view
        job = scope.launch {
            val source = combine(repository.state, selectedTargetKey) { state, selKey ->
                Pair(state, selKey)
            }
                .let { if (sampleMs > 0) it.sample(sampleMs) else it }
                .distinctUntilChanged()

            source.collect { (state, selKey) ->
                val rows = buildRows(state, selKey)
                this@PerfPresenter.view?.showList(rows)
                val selSeries = selKey?.let {
                    state.series[it]?.snapshot() ?: emptyList()
                } ?: emptyList()
                this@PerfPresenter.view?.showDetail(state.detail, selSeries)
            }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        view = null
    }

    fun selectTarget(targetKey: String?) {
        selectedTargetKey.value = targetKey
        val pid = targetKey?.let { key ->
            repository.state.value.series[key]?.snapshot()?.lastOrNull()?.value?.pid
        }
        onSelectPid(pid)
    }

    private fun buildRows(state: PerfRepository.Snapshot, selectedKey: String?): List<ProcessRow> {
        return state.series.values.map { series ->
            val last = series.snapshot().lastOrNull()?.value
                ?: return@map null
            val displayName = when (val t = last.target) {
                is com.debugtools.perfmon.data.ProcessTarget.ByName -> t.processName
                is com.debugtools.perfmon.data.ProcessTarget.ByPid -> "pid ${t.pid}"
            }
            ProcessRow(
                targetKey = last.target.key,
                displayName = displayName,
                pid = last.pid,
                cpuPercent = last.cpuPercent,
                rssBytes = last.rssBytes,
                threadCount = last.threadCount,
                alive = last.alive,
                selected = (selectedKey == last.target.key)
            )
        }.filterNotNull()
    }
}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.PerfPresenterTest" 2>&1 | tail -10
```
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/presenter/ debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/presenter/ && git commit -m "feat(perfmon): add PerfView interface, ProcessRow, PerfPresenter"
```

---

## Task 13: LineChartView Widget

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/widget/LineChartView.kt`

Custom Canvas-drawn line chart accepting one or more `Series` (label + color + List<Float>). Auto-scales Y axis. No tests — pure rendering.

- [ ] **Step 1: Create `LineChartView.kt`**

```kotlin
package com.debugtools.perfmon.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Simple multi-series line chart. Caller updates via [setSeries].
 * Auto-scales Y to [0, maxValue * 1.1f].
 */
@SuppressLint("ViewConstructor")
class LineChartView(context: Context) : View(context) {

    data class Series(val label: String, val color: Int, val values: List<Float>)

    private var seriesList: List<Series> = emptyList()
    private var yMax: Float = 100f

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#4A5568"); strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0"); textSize = 26f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
    }

    fun setSeries(series: List<Series>, yAxisMax: Float? = null) {
        seriesList = series
        val maxFromValues = series.flatMap { it.values }.maxOrNull() ?: 100f
        yMax = (yAxisMax ?: (maxFromValues * 1.1f)).coerceAtLeast(1f)
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (180 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val chartLeft = 80f
        val chartTop = 24f
        val chartRight = (width - 16).toFloat()
        val chartBottom = (height - 40).toFloat()
        val chartHeight = chartBottom - chartTop
        val chartWidth = chartRight - chartLeft

        // Y axis labels (0, mid, max)
        canvas.drawText("%.0f".format(yMax), 8f, chartTop + 12f, labelPaint)
        canvas.drawText("%.0f".format(yMax / 2), 8f, chartTop + chartHeight / 2 + 8f, labelPaint)
        canvas.drawText("0", 8f, chartBottom, labelPaint)

        // Grid lines
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, gridPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, gridPaint)
        canvas.drawLine(chartLeft, chartTop + chartHeight / 2, chartRight, chartTop + chartHeight / 2, gridPaint)

        // Lines
        for (series in seriesList) {
            if (series.values.size < 2) continue
            linePaint.color = series.color
            val path = Path()
            val stepX = chartWidth / (series.values.size - 1)
            for ((i, v) in series.values.withIndex()) {
                val x = chartLeft + i * stepX
                val y = chartBottom - (v / yMax) * chartHeight
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, linePaint)
        }

        // Legend (top-right)
        var legendY = chartTop + 16f
        for (series in seriesList) {
            labelPaint.color = series.color
            canvas.drawText(series.label, chartRight - 200f, legendY, labelPaint)
            legendY += 36f
        }
        labelPaint.color = Color.parseColor("#E2E8F0")  // reset
    }
}
```

- [ ] **Step 2: Verify build**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/widget/LineChartView.kt && git commit -m "feat(perfmon): add LineChartView for multi-series time chart"
```

---

## Task 14: ThreadBarView + ThreadStateView Widgets

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/widget/ThreadBarView.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/widget/ThreadStateView.kt`

- [ ] **Step 1: Create `ThreadBarView.kt`**

```kotlin
package com.debugtools.perfmon.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.perfmon.data.ThreadInfo

/**
 * Renders a Top-N thread list as labeled horizontal bars. Each row shows the thread
 * name on the left, the CPU% number, then a bar whose width reflects percent.
 */
@SuppressLint("ViewConstructor")
class ThreadBarView(context: Context) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        setPadding(16, 16, 16, 16)
    }

    fun setThreads(threads: List<ThreadInfo>, maxPercent: Float) {
        removeAllViews()
        for (t in threads) {
            addView(buildRow(t, maxPercent))
        }
    }

    private fun buildRow(t: ThreadInfo, maxPercent: Float): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        row.addView(TextView(context).apply {
            text = t.name
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 3f)
        })
        row.addView(TextView(context).apply {
            text = "%.1f%%".format(t.cpuPercent)
            setTextColor(Color.parseColor("#CBD5E0"))
            textSize = 14f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        // Visual bar
        val bar = View(context).apply {
            val ratio = if (maxPercent > 0) (t.cpuPercent / maxPercent).coerceIn(0f, 1f) else 0f
            setBackgroundColor(barColor(t.cpuPercent))
            layoutParams = LayoutParams(0, (16 * resources.displayMetrics.density).toInt(), ratio * 4f)
        }
        row.addView(bar)
        // Spacer to fill remaining flex
        row.addView(View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, (1f - ((t.cpuPercent / maxPercent).coerceIn(0f, 1f))) * 4f)
        })
        return row
    }

    private fun barColor(percent: Float): Int = when {
        percent < 30f -> Color.parseColor("#63B3ED")
        percent < 60f -> Color.parseColor("#FBD38D")
        else -> Color.parseColor("#FC8181")
    }
}
```

- [ ] **Step 2: Create `ThreadStateView.kt`**

```kotlin
package com.debugtools.perfmon.view.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.debugtools.perfmon.data.ThreadState

/**
 * Horizontal stacked bar showing thread-state distribution (R/S/D/Z/T) with counts.
 */
@SuppressLint("ViewConstructor")
class ThreadStateView(context: Context) : View(context) {

    private var distribution: Map<ThreadState, Int> = emptyMap()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f
    }

    fun setDistribution(d: Map<ThreadState, Int>) {
        distribution = d
        postInvalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (96 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val total = distribution.values.sum().coerceAtLeast(1)
        val barTop = 24f
        val barBottom = (height - 24).toFloat()
        val barLeft = 16f
        val barRight = (width - 16).toFloat()

        var cursorX = barLeft
        val order = listOf(
            ThreadState.RUNNING to Color.parseColor("#68D391"),
            ThreadState.SLEEPING to Color.parseColor("#63B3ED"),
            ThreadState.DISK_WAIT to Color.parseColor("#FBD38D"),
            ThreadState.ZOMBIE to Color.parseColor("#FC8181"),
            ThreadState.STOPPED to Color.parseColor("#A0AEC0"),
            ThreadState.UNKNOWN to Color.parseColor("#718096")
        )
        for ((state, color) in order) {
            val count = distribution[state] ?: 0
            if (count == 0) continue
            val width = (count.toFloat() / total) * (barRight - barLeft)
            barPaint.color = color
            canvas.drawRect(cursorX, barTop, cursorX + width, barBottom, barPaint)
            canvas.drawText("${state.name.first()} $count", cursorX + 8f, barBottom - 12f, labelPaint)
            cursorX += width
        }
    }
}
```

- [ ] **Step 3: Verify build**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/widget/ThreadBarView.kt debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/widget/ThreadStateView.kt && git commit -m "feat(perfmon): add ThreadBarView (Top N CPU) and ThreadStateView (state distribution)"
```

---

## Task 15: ProcessListView (Left Side)

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/ProcessListView.kt`

Vertical list of `ProcessRow`s with CPU/RSS/thread-count summary. Click selects.

- [ ] **Step 1: Create `ProcessListView.kt`**

```kotlin
package com.debugtools.perfmon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.presenter.ProcessRow

@SuppressLint("ViewConstructor")
class ProcessListView(context: Context, private val config: Config) : ScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#1A202C"))
    }
    var onSelect: ((String) -> Unit)? = null

    init {
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun submit(rows: List<ProcessRow>) {
        container.removeAllViews()
        for (row in rows) {
            container.addView(buildRow(row))
        }
    }

    private fun buildRow(row: ProcessRow): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = (96 * resources.displayMetrics.density).toInt()
        setPadding(24, 16, 24, 16)
        setBackgroundColor(if (row.selected) Color.parseColor("#2D3748") else Color.TRANSPARENT)
        setOnClickListener { onSelect?.invoke(row.targetKey) }

        addView(TextView(context).apply {
            text = (if (row.selected) "▶ " else "") +
                (if (!row.alive) "✗ " else "● ") +
                row.displayName +
                (row.pid?.let { " ($it)" } ?: "")
            setTextColor(if (row.alive) Color.WHITE else Color.parseColor("#A0AEC0"))
            textSize = 15f
        })
        addView(TextView(context).apply {
            text = "CPU %.0f%%   RSS %s   线程 %d".format(
                row.cpuPercent, formatBytes(row.rssBytes), row.threadCount
            )
            setTextColor(cpuColor(row.cpuPercent))
            textSize = 13f
            setPadding(0, 8, 0, 0)
        })
    }

    private fun cpuColor(percent: Float): Int = when {
        percent < config.cpuOrangeThreshold -> Color.parseColor("#68D391")
        percent < config.cpuRedThreshold -> Color.parseColor("#FBD38D")
        else -> Color.parseColor("#FC8181")
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        b < 1024 * 1024 * 1024 -> "${b / (1024 * 1024)}MB"
        else -> "${b / (1024 * 1024 * 1024)}GB"
    }
}
```

- [ ] **Step 2: Verify build**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/ProcessListView.kt && git commit -m "feat(perfmon): add ProcessListView for left-side process summary list"
```

---

## Task 16: ProcessDetailView (Right Side)

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/ProcessDetailView.kt`

Right-side detail with two LineCharts (CPU + memory) + ThreadBarView (Top N) + ThreadStateView.

- [ ] **Step 1: Create `ProcessDetailView.kt`**

```kotlin
package com.debugtools.perfmon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimedValue
import com.debugtools.perfmon.view.widget.LineChartView
import com.debugtools.perfmon.view.widget.ThreadBarView
import com.debugtools.perfmon.view.widget.ThreadStateView

@SuppressLint("ViewConstructor")
class ProcessDetailView(context: Context, private val config: Config) : ScrollView(context) {

    private val cpuChart = LineChartView(context)
    private val memChart = LineChartView(context)
    private val threadBar = ThreadBarView(context)
    private val threadState = ThreadStateView(context)
    private val title: TextView
    private val pssText: TextView
    private val placeholder: TextView

    init {
        setBackgroundColor(Color.parseColor("#1A1A2E"))
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        title = TextView(context).apply {
            text = "请在左侧选择一个进程"
            setTextColor(Color.WHITE); textSize = 18f
            setPadding(0, 0, 0, 16)
        }
        pssText = TextView(context).apply {
            setTextColor(Color.parseColor("#E2E8F0")); textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        placeholder = TextView(context).apply {
            text = "（等待数据）"
            setTextColor(Color.parseColor("#A0AEC0")); textSize = 14f
        }
        container.addView(title)
        container.addView(label("CPU% 趋势"))
        container.addView(cpuChart)
        container.addView(label("内存 PSS（KB）"))
        container.addView(memChart)
        container.addView(pssText)
        container.addView(label("Top ${config.topThreadCount} 线程 CPU%"))
        container.addView(threadBar)
        container.addView(label("线程状态分布"))
        container.addView(threadState)
        container.addView(placeholder)
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#63B3ED"))
        textSize = 12f
        setPadding(0, 16, 0, 8)
    }

    fun update(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>) {
        if (cpuSeries.isEmpty()) {
            title.text = "请在左侧选择一个进程"
            placeholder.visibility = android.view.View.VISIBLE
            return
        }
        placeholder.visibility = android.view.View.GONE
        val last = cpuSeries.last().value
        val displayName = (last.target as? com.debugtools.perfmon.data.ProcessTarget.ByName)?.processName
            ?: "pid ${last.pid}"
        title.text = "$displayName (${last.pid ?: "-"})"

        cpuChart.setSeries(listOf(
            LineChartView.Series("CPU%", Color.parseColor("#63B3ED"),
                cpuSeries.map { it.value.cpuPercent })
        ), yAxisMax = null)

        if (detail != null) {
            pssText.text = "PSS 总: ${detail.totalPssKb} KB  " +
                "Java: ${detail.dalvikPssKb} KB  " +
                "Native: ${detail.nativePssKb} KB  " +
                "Other: ${detail.otherPssKb} KB"
            memChart.setSeries(listOf(
                LineChartView.Series("PSS", Color.parseColor("#68D391"),
                    cpuSeries.map { it.value.rssBytes / 1024f })
            ), yAxisMax = null)
            threadBar.setThreads(detail.threads, maxPercent = (detail.threads.maxOfOrNull { it.cpuPercent } ?: 100f))
            threadState.setDistribution(detail.threadStateDistribution)
        } else {
            pssText.text = "PSS 数据未就绪（Tier 2 采样中…）"
        }
    }
}
```

- [ ] **Step 2: Verify build**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:assembleDebug 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/ProcessDetailView.kt && git commit -m "feat(perfmon): add ProcessDetailView (CPU+mem charts, top threads, state bar)"
```

---

## Task 17: PerfRootView + Module Wiring

**Files:**
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/PerfRootView.kt`
- Create: `debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/PerfMonitorModule.kt`
- Create: `debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/PerfMonitorModuleTest.kt`

`PerfRootView`: horizontal LinearLayout (left 30% list + right 70% detail).  
`PerfMonitorModule`: DebugModule entry, wires repository + samplers + presenter + view in onAttach.

- [ ] **Step 1: Create `PerfRootView.kt`**

```kotlin
package com.debugtools.perfmon.view

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.TimedValue
import com.debugtools.perfmon.presenter.PerfView
import com.debugtools.perfmon.presenter.ProcessRow

@SuppressLint("ViewConstructor")
class PerfRootView(
    context: Context,
    config: Config,
    onSelect: (String) -> Unit
) : LinearLayout(context), PerfView {

    private val listView = ProcessListView(context, config).also { it.onSelect = onSelect }
    private val detailView = ProcessDetailView(context, config)

    init {
        orientation = HORIZONTAL
        addView(listView, LayoutParams(0, LayoutParams.MATCH_PARENT, 3f))
        addView(detailView, LayoutParams(0, LayoutParams.MATCH_PARENT, 7f))
    }

    override fun showList(rows: List<ProcessRow>) {
        listView.submit(rows)
    }

    override fun showDetail(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>) {
        detailView.update(detail, cpuSeries)
    }
}
```

- [ ] **Step 2: Write failing test for `PerfMonitorModule`**

```kotlin
package com.debugtools.perfmon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.debugtools.perfmon.data.ProcessTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerfMonitorModuleTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun `moduleId and tabTitle are set`() {
        val m = PerfMonitorModule.builder()
            .addProcessByName("com.example")
            .build()
        assertEquals("debugtools_perfmon", m.moduleId)
        assertEquals("性能监控", m.tabTitle)
    }

    @Test fun `builder stores targets`() {
        val m = PerfMonitorModule.builder()
            .addProcessByName("a")
            .addProcessByPid(123)
            .build()
        assertEquals(2, m.targetsForTest().size)
        assertTrue(m.targetsForTest().any { it is ProcessTarget.ByName && it.processName == "a" })
        assertTrue(m.targetsForTest().any { it is ProcessTarget.ByPid && it.pid == 123 })
    }

    @Test fun `builder clamps updateIntervalSec to range`() {
        val tooSmall = PerfMonitorModule.builder().updateIntervalSec(1).addProcessByPid(1).build()
        assertEquals(5, tooSmall.configForTest().updateIntervalSec)
        val tooBig = PerfMonitorModule.builder().updateIntervalSec(120).addProcessByPid(1).build()
        assertEquals(60, tooBig.configForTest().updateIntervalSec)
    }
}
```

- [ ] **Step 3: Run test to confirm failure**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.PerfMonitorModuleTest" 2>&1 | tail -10
```
Expected: compile errors.

- [ ] **Step 4: Create `PerfMonitorModule.kt`**

```kotlin
package com.debugtools.perfmon

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.presenter.PerfPresenter
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.sampler.Tier1Sampler
import com.debugtools.perfmon.sampler.Tier2Sampler
import com.debugtools.perfmon.source.MemInfoReader
import com.debugtools.perfmon.source.ProcDiscoverer
import com.debugtools.perfmon.source.ProcStatReader
import com.debugtools.perfmon.source.ProcStatmReader
import com.debugtools.perfmon.source.ThreadReader
import com.debugtools.perfmon.view.PerfRootView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

class PerfMonitorModule private constructor(
    private val config: Config,
    private val targets: List<ProcessTarget>
) : DebugModule {

    override val moduleId: String = "debugtools_perfmon"
    override val tabTitle: String = "性能监控"

    private val repository = PerfRepository(config)
    private var presenter: PerfPresenter? = null
    private var tier1: Tier1Sampler? = null
    private var tier2: Tier2Sampler? = null
    private var ioScope: CoroutineScope? = null
    private var mainScope: CoroutineScope? = null
    private var rootView: PerfRootView? = null

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun createContentView(context: Context): View {
        val view = PerfRootView(
            context = context,
            config = config,
            onSelect = { presenter?.selectTarget(it) }
        )
        rootView = view
        presenter?.attachView(view)
        return view
    }

    override fun getBriefItems(): List<BriefItem> {
        val snap = repository.state.value
        val last = snap.series.values.mapNotNull { it.snapshot().lastOrNull()?.value }
        val anyRed = last.any { it.cpuPercent >= config.cpuRedThreshold }
        return listOf(
            BriefItem(
                text = "${last.size}P · CPU max %.0f%%".format(last.maxOfOrNull { it.cpuPercent } ?: 0f),
                color = if (anyRed) android.graphics.Color.parseColor("#FC8181") else null
            )
        )
    }

    override fun onAttach(context: Context, storage: SettingsStorage) {
        val app = context.applicationContext
        val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val main = CoroutineScope(Dispatchers.Main + SupervisorJob())
        ioScope = io
        mainScope = main

        val procRoot = File("/proc")
        val tier1Sampler = Tier1Sampler(
            targets = targets,
            repository = repository,
            config = config,
            discoverer = ProcDiscoverer(procRoot),
            statReader = ProcStatReader(procRoot),
            statmReader = ProcStatmReader(procRoot),
            threadReader = ThreadReader(procRoot)
        )
        val tier2Sampler = Tier2Sampler(
            repository = repository,
            config = config,
            memReader = MemInfoReader(app),
            threadReader = ThreadReader(procRoot)
        )
        tier1 = tier1Sampler.also { it.start(io) }
        tier2 = tier2Sampler.also { it.start(io) }

        val p = PerfPresenter(
            repository = repository,
            scope = main,
            onSelectPid = { pid -> tier2?.selectPid(pid) }
        )
        presenter = p
        rootView?.let { p.attachView(it) }
    }

    override fun onDetach() {
        presenter?.detach()
        presenter = null
        tier1?.stop()
        tier2?.stop()
        ioScope?.cancel()
        mainScope?.cancel()
        ioScope = null
        mainScope = null
    }

    internal fun targetsForTest(): List<ProcessTarget> = targets
    internal fun configForTest(): Config = config

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
        fun topThreadCount(n: Int) = apply {
            config = config.copy(topThreadCount = n.coerceIn(3, 50))
        }
        fun build() = PerfMonitorModule(config, targets.toList())
    }

    companion object {
        fun builder() = Builder()
    }
}
```

- [ ] **Step 5: Run tests**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test --tests "*.PerfMonitorModuleTest" 2>&1 | tail -10
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:assembleDebug 2>&1 | tail -5
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-perfmon:test 2>&1 | tail -5
```
Expected: 3 module tests PASS; all module tests PASS; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/view/PerfRootView.kt debugtools-perfmon/src/main/kotlin/com/debugtools/perfmon/PerfMonitorModule.kt debugtools-perfmon/src/test/kotlin/com/debugtools/perfmon/PerfMonitorModuleTest.kt && git commit -m "feat(perfmon): add PerfRootView dual-column layout and PerfMonitorModule entry"
```

---

## Task 18: Sample App Integration

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`

- [ ] **Step 1: Add dependency to `app/build.gradle.kts`**

Open `/Users/xianxiaoli/ClaudeProjects/DebugTools/app/build.gradle.kts`. Find the `dependencies { ... }` block. After the existing `implementation(project(":debugtools-okhttp-capture"))` line, add:

```kotlin
    implementation(project(":debugtools-perfmon"))
```

- [ ] **Step 2: Modify `MainActivity.kt`**

Read the current `MainActivity.kt` first to understand the layout.

Then:

1. Add this import at the top:
```kotlin
import com.debugtools.perfmon.PerfMonitorModule
```

2. Add a new field next to the other module fields (e.g. near `captureModule`):
```kotlin
    private val perfModule = PerfMonitorModule.builder()
        .addProcessByName(packageName)
        .updateIntervalSec(10)
        .windowMin(30)
        .build()
```

Note: this line uses `packageName` which is an instance method on Activity — it's not available in field initializer order. To fix: change to lateinit and initialize in onCreate, OR use a property delegate that defers evaluation. Use `by lazy`:

```kotlin
    private val perfModule by lazy {
        PerfMonitorModule.builder()
            .addProcessByName(packageName)
            .updateIntervalSec(10)
            .windowMin(30)
            .build()
    }
```

3. In `initDebugTools()`, find the existing `.register(captureModule)` and add this line right after:
```kotlin
                .register(perfModule)
```

- [ ] **Step 3: Build and verify all tests still pass**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :app:assembleDebug 2>&1 | tail -5
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew test 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL for both.

- [ ] **Step 4: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add app/ && git commit -m "feat(app): integrate debugtools-perfmon monitoring own process"
```

---

## Self-Review Notes

Verified against spec §1–§12:

| Spec section | Tasks |
|---|---|
| §3 Module layout | T1 |
| §4 PerfMonitorModule public API (Builder, ProcessTarget, Config) | T2, T3, T17 |
| §5 Two-tier sampling architecture | T10, T11 |
| §6.1 ProcStatReader (differential CPU%) | T4 |
| §6.2 ProcStatmReader (RSS) | T5 |
| §6.3 MemInfoReader (PSS / dalvikPss / nativePss) | T8 |
| §6.4 ThreadReader (enumeration + per-thread CPU) | T7 |
| §6.5 ProcDiscoverer (name→pid + restart) | T6 |
| §7 Data models | T2 |
| §8 Data flow (Repository + Presenter + sample throttling) | T9, T12 |
| §9 UI dual-column + charts | T13, T14, T15, T16, T17 |
| §10 Performance budget (Tier 1 cheap, Tier 2 only when selected) | T10, T11 |
| §11 Error handling (missing pid, SELinux, restart) | T6, T7, T8, T10, T11 |
| §12 Known limitations (system app requirement, multi-core > 100%, no persistence) | doc in spec |

Notes after self-review:
- ProcStatReader's CPU% calc is `fraction * 100 * coreCount` (top-style multi-core view, consistent with spec §6.1 and known limit "may > 100%").
- `frames`/series eviction tested via TimeSeries unit tests (T2) plus Tier1Sampler integration test (T10).
- Tier 2 sampler's pid switching logic is tested by selectPid(null)→clearDetail flow (T11).
- Brief info uses red color when any sampled process is above red threshold (T17).
- `Tier1Sampler` calls `delay(intervalSec * 1000L)` after first sample so the first sample happens immediately on start; the second arrives one interval later. Tests verify this.
