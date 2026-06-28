# App 启动链路监控（debugtools-startup）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `debugtools-startup` 模块:接入方按通用协议上报启动各组件的 begin/success/fail 与依赖,SDK 记录、持久化(最近 10 次)、判定成功/失败/流程/耗时,提供甘特图+依赖图双视图与自动诊断。

**Architecture:** 纯逻辑下沉:协议数据类(JSON 序列化)、`StartupRecorder`(线程安全地累积一次会话)、`StartupStore`(落盘/LRU)、`StartupAnalyzer`+`CriticalPath`(诊断/关键路径)全部纯 JVM 可测;`AppStartupMonitor`(进程单例)是 Android 粘合(进程启动时间、生命周期兜底、持久化);`StartupMonitorModule` + 视图是展示层。

**Tech Stack:** Kotlin, Android(compileSdk 34, minSdk 26), Android 内置 `org.json`(生产)+ `org.json:json`(测试), JUnit4, Android Canvas。

**参考规格:** `docs/superpowers/specs/2026-06-28-startup-monitor-design.md`

---

## File Structure

新增模块 `debugtools-startup/`:
- `protocol/StepStatus.kt`、`protocol/StartupStep.kt`、`protocol/StartupSession.kt`、`protocol/StartupIssue.kt` — 协议 + JSON
- `recorder/StartupRecorder.kt` — 纯逻辑会话累积器
- `store/StartupStore.kt` — 落盘 / 读取 / LRU
- `analyzer/StartupAnalyzer.kt`、`analyzer/CriticalPath.kt` — 纯诊断逻辑
- `AppStartupMonitor.kt` — 进程单例(Android 粘合)
- `view/StartupColors.kt`、`view/SessionListView.kt`、`view/GanttView.kt`、`view/DagView.kt`、`view/StartupRootView.kt`
- `StartupMonitorModule.kt` — DebugModule 入口
- 测试:`StartupSessionJsonTest`、`StartupRecorderTest`、`StartupStoreTest`、`StartupAnalyzerTest`、`CriticalPathTest`
- `build.gradle.kts`、`src/main/AndroidManifest.xml`

修改:`settings.gradle.kts`(include)、`app/`(demo 接入)。

---

## Task 1: 模块骨架(gradle + manifest + settings)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `debugtools-startup/build.gradle.kts`
- Create: `debugtools-startup/src/main/AndroidManifest.xml`

- [ ] **Step 1: settings.gradle.kts 加入模块**

在 `include(":debugtools-audiomon")` 之后加一行:
```kotlin
include(":debugtools-startup")
```

- [ ] **Step 2: 创建 build.gradle.kts**

`debugtools-startup/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.startup"
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
    testImplementation("org.json:json:20240303") // Android ships org.json; this makes it work in JVM tests
}
```

- [ ] **Step 3: 创建 AndroidManifest.xml**

`debugtools-startup/src/main/AndroidManifest.xml`:
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: 验证构建**

Run: `./gradlew :debugtools-startup:assembleDebug`
Expected: BUILD SUCCESSFUL（空模块）。

- [ ] **Step 5: 提交**

```bash
git add settings.gradle.kts debugtools-startup/build.gradle.kts debugtools-startup/src/main/AndroidManifest.xml
git commit -m "chore(startup): scaffold debugtools-startup module"
```

---

## Task 2: 协议数据类 + JSON 序列化

**Files:**
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/protocol/StepStatus.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/protocol/StartupStep.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/protocol/StartupSession.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/protocol/StartupIssue.kt`
- Test: `debugtools-startup/src/test/kotlin/com/debugtools/startup/protocol/StartupSessionJsonTest.kt`

- [ ] **Step 1: 写失败测试**

`StartupSessionJsonTest.kt`:
```kotlin
package com.debugtools.startup.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupSessionJsonTest {

    private fun sample() = StartupSession(
        sessionId = "s1",
        startedAtWallMs = 1000L,
        launchUptimeMs = 50L,
        appVersion = "1.2.3",
        steps = listOf(
            StartupStep("config", emptyList(), 60L, 70L, StepStatus.SUCCESS, null, "main"),
            StartupStep("asr", listOf("config"), 72L, 130L, StepStatus.FAILED, "BootException: boom", "init-1"),
            StartupStep("net", emptyList(), 60L, null, StepStatus.RUNNING, null, "io-2")
        ),
        completedUptimeMs = 130L,
        completedExplicitly = true
    )

    @Test fun `session round-trips through json`() {
        val json = sample().toJson()
        val back = StartupSession.fromJson(JSONObject(json.toString()))
        assertEquals(sample(), back)
    }

    @Test fun `nulls survive round-trip`() {
        val back = StartupSession.fromJson(JSONObject(sample().toJson().toString()))
        val running = back.steps.first { it.name == "net" }
        assertNull(running.endUptimeMs)
        assertNull(running.error)
        assertEquals(StepStatus.RUNNING, running.status)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-startup:test`
Expected: FAIL（类型未定义）。

- [ ] **Step 3: 实现四个文件**

`StepStatus.kt`:
```kotlin
package com.debugtools.startup.protocol

/** A startup step is RUNNING until it ends as SUCCESS or FAILED (an error also ends it). */
enum class StepStatus { RUNNING, SUCCESS, FAILED }
```

`StartupStep.kt`:
```kotlin
package com.debugtools.startup.protocol

import org.json.JSONArray
import org.json.JSONObject

/** One component's initialization. [name] is unique within a session. Times are SystemClock.uptimeMillis(). */
data class StartupStep(
    val name: String,
    val dependsOn: List<String>,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,        // null while RUNNING
    val status: StepStatus,
    val error: String?,            // set when FAILED
    val thread: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("dependsOn", JSONArray(dependsOn))
        put("startUptimeMs", startUptimeMs)
        put("endUptimeMs", endUptimeMs ?: JSONObject.NULL)
        put("status", status.name)
        put("error", error ?: JSONObject.NULL)
        put("thread", thread)
    }

    companion object {
        fun fromJson(o: JSONObject): StartupStep {
            val depsArr = o.getJSONArray("dependsOn")
            val deps = (0 until depsArr.length()).map { depsArr.getString(it) }
            return StartupStep(
                name = o.getString("name"),
                dependsOn = deps,
                startUptimeMs = o.getLong("startUptimeMs"),
                endUptimeMs = if (o.isNull("endUptimeMs")) null else o.getLong("endUptimeMs"),
                status = StepStatus.valueOf(o.getString("status")),
                error = if (o.isNull("error")) null else o.getString("error"),
                thread = o.getString("thread")
            )
        }
    }
}
```

`StartupSession.kt`:
```kotlin
package com.debugtools.startup.protocol

import org.json.JSONArray
import org.json.JSONObject

/** One full app-launch session: t0 = process start, plus the steps and a completion marker. */
data class StartupSession(
    val sessionId: String,
    val startedAtWallMs: Long,
    val launchUptimeMs: Long,
    val appVersion: String?,
    val steps: List<StartupStep>,
    val completedUptimeMs: Long?,
    val completedExplicitly: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("startedAtWallMs", startedAtWallMs)
        put("launchUptimeMs", launchUptimeMs)
        put("appVersion", appVersion ?: JSONObject.NULL)
        put("completedUptimeMs", completedUptimeMs ?: JSONObject.NULL)
        put("completedExplicitly", completedExplicitly)
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): StartupSession {
            val arr = o.getJSONArray("steps")
            val steps = (0 until arr.length()).map { StartupStep.fromJson(arr.getJSONObject(it)) }
            return StartupSession(
                sessionId = o.getString("sessionId"),
                startedAtWallMs = o.getLong("startedAtWallMs"),
                launchUptimeMs = o.getLong("launchUptimeMs"),
                appVersion = if (o.isNull("appVersion")) null else o.getString("appVersion"),
                steps = steps,
                completedUptimeMs = if (o.isNull("completedUptimeMs")) null else o.getLong("completedUptimeMs"),
                completedExplicitly = o.getBoolean("completedExplicitly")
            )
        }
    }
}
```

`StartupIssue.kt`:
```kotlin
package com.debugtools.startup.protocol

/** A problem the analyzer found in a session. */
data class StartupIssue(
    val type: IssueType,
    val stepName: String?,
    val detail: String,
    val severity: Severity
)

enum class IssueType { ERROR, SLOW, DEP_VIOLATION, NEVER_ENDED, DEP_CYCLE, PARALLELIZABLE }
enum class Severity { ERROR, WARN, INFO }
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-startup:test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add debugtools-startup/src/main/kotlin/com/debugtools/startup/protocol/ \
  debugtools-startup/src/test/kotlin/com/debugtools/startup/protocol/StartupSessionJsonTest.kt
git commit -m "feat(startup): add startup protocol data classes with JSON serialization"
```

---

## Task 3: StartupRecorder（线程安全会话累积器,纯逻辑）

**Files:**
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/recorder/StartupRecorder.kt`
- Test: `debugtools-startup/src/test/kotlin/com/debugtools/startup/recorder/StartupRecorderTest.kt`

- [ ] **Step 1: 写失败测试**

`StartupRecorderTest.kt`:
```kotlin
package com.debugtools.startup.recorder

import com.debugtools.startup.protocol.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecorderTest {

    /** Deterministic clock: each tick() advances by 10ms; begin/success read it. */
    private class Clock(var now: Long = 0L) { fun read() = now }

    private fun recorder(clock: Clock) = StartupRecorder(
        sessionId = "s1", launchUptimeMs = 0L, startedAtWallMs = 1000L,
        appVersion = "1.0", clock = clock::read
    )

    @Test fun `begin then success records a completed step`() {
        val c = Clock(); val r = recorder(c)
        c.now = 5; r.begin("config", emptyList())
        c.now = 15; r.success("config")
        val s = r.snapshot().steps.single()
        assertEquals("config", s.name)
        assertEquals(5L, s.startUptimeMs)
        assertEquals(15L, s.endUptimeMs)
        assertEquals(StepStatus.SUCCESS, s.status)
    }

    @Test fun `fail records FAILED with error and ends the step`() {
        val c = Clock(); val r = recorder(c)
        r.begin("asr", listOf("config"))
        r.fail("asr", "BootException: boom")
        val s = r.snapshot().steps.single()
        assertEquals(StepStatus.FAILED, s.status)
        assertTrue(s.error!!.contains("boom"))
        assertEquals(listOf("config"), s.dependsOn)
    }

    @Test fun `running step has null end and RUNNING status`() {
        val r = recorder(Clock())
        r.begin("net", emptyList())
        val s = r.snapshot().steps.single()
        assertNull(s.endUptimeMs)
        assertEquals(StepStatus.RUNNING, s.status)
    }

    @Test fun `duplicate begin and success on unknown or already-ended step are ignored`() {
        val c = Clock(); val r = recorder(c)
        c.now = 1; r.begin("a", emptyList())
        c.now = 2; r.begin("a", emptyList())     // duplicate begin -> ignored, keeps start=1
        c.now = 3; r.success("a")
        c.now = 4; r.success("a")                 // already ended -> ignored, keeps end=3
        r.success("ghost")                        // never began -> ignored, no crash
        val s = r.snapshot().steps.single()
        assertEquals(1L, s.startUptimeMs)
        assertEquals(3L, s.endUptimeMs)
    }

    @Test fun `complete marks explicit completion and is idempotent`() {
        val c = Clock(); val r = recorder(c)
        c.now = 9; r.complete()
        c.now = 20; r.complete()
        val snap = r.snapshot()
        assertEquals(9L, snap.completedUptimeMs)
        assertTrue(snap.completedExplicitly)
    }

    @Test fun `finalizeFallback completes without explicit flag and never overrides complete`() {
        val c = Clock(); val r = recorder(c)
        c.now = 7; r.finalizeFallback()
        assertEquals(7L, r.snapshot().completedUptimeMs)
        assertEquals(false, r.snapshot().completedExplicitly)
        c.now = 12; r.complete()                  // already finalized -> ignored
        assertEquals(7L, r.snapshot().completedUptimeMs)
    }

    @Test fun `steps keep insertion order`() {
        val r = recorder(Clock())
        r.begin("a", emptyList()); r.begin("b", emptyList()); r.begin("c", emptyList())
        assertEquals(listOf("a", "b", "c"), r.snapshot().steps.map { it.name })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-startup:test`
Expected: FAIL（StartupRecorder 未定义）。

- [ ] **Step 3: 实现 StartupRecorder**

```kotlin
package com.debugtools.startup.recorder

import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus

/**
 * Accumulates one startup session. Thread-safe (begin/success/fail may be called
 * from parallel init threads). Pure JVM — [clock] returns a monotonic uptime; no
 * Android dependency, so it is unit-testable with a fake clock.
 */
class StartupRecorder(
    private val sessionId: String,
    private val launchUptimeMs: Long,
    private val startedAtWallMs: Long,
    private val appVersion: String?,
    private val clock: () -> Long
) {
    private val lock = Any()
    private val steps = LinkedHashMap<String, StartupStep>() // preserves first-begin order
    private var completedUptimeMs: Long? = null
    private var completedExplicitly = false

    fun begin(name: String, dependsOn: List<String>) = synchronized(lock) {
        if (steps.containsKey(name)) return                  // duplicate begin ignored
        steps[name] = StartupStep(
            name = name, dependsOn = dependsOn, startUptimeMs = clock(),
            endUptimeMs = null, status = StepStatus.RUNNING, error = null,
            thread = Thread.currentThread().name
        )
    }

    fun success(name: String) = close(name, StepStatus.SUCCESS, null)

    fun fail(name: String, error: String?) = close(name, StepStatus.FAILED, error)

    private fun close(name: String, status: StepStatus, error: String?) = synchronized(lock) {
        val s = steps[name] ?: return                        // unknown step ignored
        if (s.endUptimeMs != null) return                    // already ended ignored
        steps[name] = s.copy(endUptimeMs = clock(), status = status, error = error)
    }

    /** Explicit "startup complete" marker. Idempotent. */
    fun complete() = synchronized(lock) {
        if (completedUptimeMs == null) { completedUptimeMs = clock(); completedExplicitly = true }
    }

    /** Safety-net completion (host forgot to call complete()). Never overrides an existing completion. */
    fun finalizeFallback() = synchronized(lock) {
        if (completedUptimeMs == null) { completedUptimeMs = clock(); completedExplicitly = false }
    }

    fun isCompleted(): Boolean = synchronized(lock) { completedUptimeMs != null }

    fun snapshot(): StartupSession = synchronized(lock) {
        StartupSession(
            sessionId = sessionId,
            startedAtWallMs = startedAtWallMs,
            launchUptimeMs = launchUptimeMs,
            appVersion = appVersion,
            steps = steps.values.toList(),
            completedUptimeMs = completedUptimeMs,
            completedExplicitly = completedExplicitly
        )
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-startup:test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add debugtools-startup/src/main/kotlin/com/debugtools/startup/recorder/StartupRecorder.kt \
  debugtools-startup/src/test/kotlin/com/debugtools/startup/recorder/StartupRecorderTest.kt
git commit -m "feat(startup): add thread-safe StartupRecorder"
```

---

## Task 4: StartupStore（落盘 / 读取 / 最近 10 次 LRU）

**Files:**
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/store/StartupStore.kt`
- Test: `debugtools-startup/src/test/kotlin/com/debugtools/startup/store/StartupStoreTest.kt`

- [ ] **Step 1: 写失败测试**

`StartupStoreTest.kt`:
```kotlin
package com.debugtools.startup.store

import com.debugtools.startup.protocol.StartupSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun session(id: String, wall: Long) = StartupSession(
        sessionId = id, startedAtWallMs = wall, launchUptimeMs = 0L, appVersion = null,
        steps = emptyList(), completedUptimeMs = 1L, completedExplicitly = true
    )

    @Test fun `save then load returns the session`() {
        val store = StartupStore(tmp.root)
        store.save(session("a", 100L))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("a", loaded.first().sessionId)
    }

    @Test fun `load returns most-recent first`() {
        val store = StartupStore(tmp.root)
        store.save(session("old", 100L))
        store.save(session("new", 200L))
        assertEquals(listOf("new", "old"), store.load().map { it.sessionId })
    }

    @Test fun `keeps only the most recent maxSessions`() {
        val store = StartupStore(tmp.root, maxSessions = 3)
        (1..5).forEach { store.save(session("s$it", it * 100L)) }
        assertEquals(listOf("s5", "s4", "s3"), store.load().map { it.sessionId })
    }

    @Test fun `corrupt file is skipped, others still load`() {
        val store = StartupStore(tmp.root)
        store.save(session("good", 100L))
        tmp.root.resolve("050_bad.json").writeText("{ not valid json")
        assertEquals(listOf("good"), store.load().map { it.sessionId })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-startup:test`
Expected: FAIL

- [ ] **Step 3: 实现 StartupStore**

```kotlin
package com.debugtools.startup.store

import com.debugtools.startup.protocol.StartupSession
import org.json.JSONObject
import java.io.File

/**
 * Persists startup sessions as JSON under [dir] (the host passes context.filesDir/startup,
 * which Android removes on uninstall). Keeps the [maxSessions] most recent files.
 */
class StartupStore(
    private val dir: File,
    private val maxSessions: Int = 10
) {
    fun save(session: StartupSession) {
        dir.mkdirs()
        // File name sorts by start time so newest = lexicographically last via padded wall ms.
        val name = "%013d_%s.json".format(session.startedAtWallMs, session.sessionId)
        File(dir, name).writeText(session.toJson().toString())
        evict()
    }

    /** Most-recent first. Unparseable files are skipped. */
    fun load(): List<StartupSession> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedByDescending { it.name }
            .mapNotNull { runCatching { StartupSession.fromJson(JSONObject(it.readText())) }.getOrNull() }
    }

    private fun evict() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return
        files.sortedByDescending { it.name }
            .drop(maxSessions)
            .forEach { it.delete() }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-startup:test`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add debugtools-startup/src/main/kotlin/com/debugtools/startup/store/StartupStore.kt \
  debugtools-startup/src/test/kotlin/com/debugtools/startup/store/StartupStoreTest.kt
git commit -m "feat(startup): add StartupStore with most-recent-10 LRU persistence"
```

---

## Task 5: StartupAnalyzer + CriticalPath（诊断,纯逻辑）

**Files:**
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/analyzer/CriticalPath.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/analyzer/StartupAnalyzer.kt`
- Test: `debugtools-startup/src/test/kotlin/com/debugtools/startup/analyzer/CriticalPathTest.kt`
- Test: `debugtools-startup/src/test/kotlin/com/debugtools/startup/analyzer/StartupAnalyzerTest.kt`

> 规则(默认慢阈值 50ms):
> - `ERROR`: status==FAILED。
> - `SLOW`: 已结束(end!=null)且 `end-start > 50`。
> - `DEP_VIOLATION`: 某依赖存在且已结束,且 `step.start < dep.end`。
> - `NEVER_ENDED`: 会话已完成(completedUptimeMs!=null)但 step 仍 RUNNING。
> - `DEP_CYCLE`: dependsOn 图存在环。
> - `PARALLELIZABLE`(info): step 无依赖(dependsOn 空)却 `start-launch > 50`(无依赖却延迟开始,可提前并行)。

- [ ] **Step 1: 写 CriticalPath 失败测试**

`CriticalPathTest.kt`:
```kotlin
package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CriticalPathTest {

    private fun step(name: String, deps: List<String>, start: Long, end: Long?) =
        StartupStep(name, deps, start, end,
            if (end == null) StepStatus.RUNNING else StepStatus.SUCCESS, null, "t")

    private fun session(vararg steps: StartupStep) = StartupSession(
        "s", 0L, 0L, null, steps.toList(), steps.mapNotNull { it.endUptimeMs }.maxOrNull(), true
    )

    @Test fun `path walks back via the latest-finishing dependency`() {
        // config(0-10) -> asr(10-40) -> tts(40-80); net(0-20) independent, finishes earlier than tts
        val s = session(
            step("config", emptyList(), 0, 10),
            step("asr", listOf("config"), 10, 40),
            step("tts", listOf("asr"), 40, 80),
            step("net", emptyList(), 0, 20)
        )
        assertEquals(listOf("config", "asr", "tts"), CriticalPath.of(s))
    }

    @Test fun `no-dependency terminal yields a single element`() {
        val s = session(step("only", emptyList(), 0, 30))
        assertEquals(listOf("only"), CriticalPath.of(s))
    }

    @Test fun `empty session yields empty path`() {
        assertEquals(emptyList<String>(), CriticalPath.of(session()))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-startup:test`
Expected: FAIL

- [ ] **Step 3: 实现 CriticalPath**

```kotlin
package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep

/**
 * The dependency chain that determined total startup time: start from the
 * latest-finishing step and walk back, each time choosing the dependency that
 * finished latest, until a step with no (resolvable) dependency.
 */
object CriticalPath {
    fun of(session: StartupSession): List<String> {
        val byName = session.steps.associateBy { it.name }
        fun endOf(s: StartupStep): Long = s.endUptimeMs ?: s.startUptimeMs
        val terminal = session.steps.maxByOrNull { endOf(it) } ?: return emptyList()

        val chain = ArrayDeque<String>()
        var current: StartupStep? = terminal
        val seen = HashSet<String>()
        while (current != null && seen.add(current.name)) {
            chain.addFirst(current.name)
            val next = current.dependsOn
                .mapNotNull { byName[it] }
                .maxByOrNull { endOf(it) }
            current = next
        }
        return chain.toList()
    }
}
```

- [ ] **Step 4: 写 StartupAnalyzer 失败测试**

`StartupAnalyzerTest.kt`:
```kotlin
package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.IssueType
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StartupStep
import com.debugtools.startup.protocol.StepStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAnalyzerTest {

    private fun step(
        name: String, deps: List<String> = emptyList(), start: Long = 0, end: Long? = 1,
        status: StepStatus = StepStatus.SUCCESS, error: String? = null
    ) = StartupStep(name, deps, start, end, status, error, "t")

    private fun session(steps: List<StartupStep>, completed: Long? = 1000L) =
        StartupSession("s", 0L, launchUptimeMs = 0L, appVersion = null,
            steps = steps, completedUptimeMs = completed, completedExplicitly = true)

    private fun types(s: StartupSession) =
        StartupAnalyzer.analyze(s).map { it.type }

    @Test fun `failed step is an ERROR`() {
        val s = session(listOf(step("asr", status = StepStatus.FAILED, error = "boom")))
        assertTrue(types(s).contains(IssueType.ERROR))
    }

    @Test fun `step over slow threshold is SLOW`() {
        val s = session(listOf(step("big", start = 0, end = 80))) // 80ms > 50
        assertTrue(types(s).contains(IssueType.SLOW))
    }

    @Test fun `fast step is not SLOW`() {
        val s = session(listOf(step("quick", start = 0, end = 30))) // 30ms < 50
        assertTrue(!types(s).contains(IssueType.SLOW))
    }

    @Test fun `dependency violation when step starts before its dep ends`() {
        val s = session(listOf(
            step("config", start = 0, end = 100),
            step("asr", deps = listOf("config"), start = 40, end = 120) // 40 < 100
        ))
        assertTrue(types(s).contains(IssueType.DEP_VIOLATION))
    }

    @Test fun `running step at completion is NEVER_ENDED`() {
        val s = session(listOf(step("hang", end = null, status = StepStatus.RUNNING)))
        assertTrue(types(s).contains(IssueType.NEVER_ENDED))
    }

    @Test fun `dependency cycle is detected`() {
        val s = session(listOf(
            step("a", deps = listOf("b")),
            step("b", deps = listOf("a"))
        ))
        assertTrue(types(s).contains(IssueType.DEP_CYCLE))
    }

    @Test fun `independent step that started late is PARALLELIZABLE`() {
        val s = session(listOf(step("late", deps = emptyList(), start = 80, end = 90))) // start-launch=80 > 50
        assertTrue(types(s).contains(IssueType.PARALLELIZABLE))
    }
}
```

- [ ] **Step 5: 运行确认失败**

Run: `./gradlew :debugtools-startup:test`
Expected: FAIL（StartupAnalyzer 未定义）。

- [ ] **Step 6: 实现 StartupAnalyzer**

```kotlin
package com.debugtools.startup.analyzer

import com.debugtools.startup.protocol.IssueType
import com.debugtools.startup.protocol.Severity
import com.debugtools.startup.protocol.StartupIssue
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StepStatus

/** Pure diagnostics over a finished (or finalized) startup session. */
object StartupAnalyzer {

    const val DEFAULT_SLOW_MS = 50L

    fun analyze(session: StartupSession, slowThresholdMs: Long = DEFAULT_SLOW_MS): List<StartupIssue> {
        val issues = mutableListOf<StartupIssue>()
        val byName = session.steps.associateBy { it.name }

        for (s in session.steps) {
            val dur = if (s.endUptimeMs != null) s.endUptimeMs - s.startUptimeMs else null

            if (s.status == StepStatus.FAILED) {
                issues += StartupIssue(IssueType.ERROR, s.name, s.error ?: "初始化失败", Severity.ERROR)
            }
            if (dur != null && dur > slowThresholdMs) {
                issues += StartupIssue(IssueType.SLOW, s.name, "耗时 ${dur}ms", Severity.WARN)
            }
            if (session.completedUptimeMs != null && s.endUptimeMs == null) {
                issues += StartupIssue(IssueType.NEVER_ENDED, s.name, "完成时仍未结束(漏 end/卡死)", Severity.WARN)
            }
            for (depName in s.dependsOn) {
                val dep = byName[depName] ?: continue
                if (dep.endUptimeMs != null && s.startUptimeMs < dep.endUptimeMs) {
                    issues += StartupIssue(
                        IssueType.DEP_VIOLATION, s.name,
                        "在依赖 $depName 完成前就开始(顺序 race)", Severity.WARN
                    )
                }
            }
            if (s.dependsOn.isEmpty() && (s.startUptimeMs - session.launchUptimeMs) > slowThresholdMs) {
                issues += StartupIssue(
                    IssueType.PARALLELIZABLE, s.name,
                    "无依赖却延迟 ${s.startUptimeMs - session.launchUptimeMs}ms 才开始,可提前并行", Severity.INFO
                )
            }
        }

        if (hasCycle(byName)) {
            issues += StartupIssue(IssueType.DEP_CYCLE, null, "dependsOn 存在依赖环", Severity.ERROR)
        }
        return issues
    }

    private fun hasCycle(byName: Map<String, com.debugtools.startup.protocol.StartupStep>): Boolean {
        val state = HashMap<String, Int>() // 0=unseen,1=in-stack,2=done
        fun dfs(name: String): Boolean {
            when (state[name]) { 1 -> return true; 2 -> return false }
            state[name] = 1
            for (d in byName[name]?.dependsOn ?: emptyList()) {
                if (byName.containsKey(d) && dfs(d)) return true
            }
            state[name] = 2
            return false
        }
        return byName.keys.any { dfs(it) }
    }
}
```

- [ ] **Step 7: 运行确认通过**

Run: `./gradlew :debugtools-startup:test`
Expected: PASS（CriticalPath + Analyzer 共 10 测试）。

- [ ] **Step 8: 提交**

```bash
git add debugtools-startup/src/main/kotlin/com/debugtools/startup/analyzer/ \
  debugtools-startup/src/test/kotlin/com/debugtools/startup/analyzer/
git commit -m "feat(startup): add StartupAnalyzer diagnostics and CriticalPath"
```

---

## Task 6: AppStartupMonitor（进程单例,Android 粘合）

**Files:**
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/AppStartupMonitor.kt`

> 粘合层(进程启动时间、生命周期兜底、落盘),无单测;编译验证。核心逻辑已在 StartupRecorder/Store(已测)。
>
> 注:spec §4 把 `markLaunch()` 列为**可选**——本计划**不实现**它(YAGNI)。t0 一律用 `Process.getStartUptimeMillis()`(进程启动时间),比手动标记更准、覆盖 SDK 初始化前那段。若日后确需自定义 t0 再加。

- [ ] **Step 1: 实现 AppStartupMonitor**

```kotlin
package com.debugtools.startup

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.recorder.StartupRecorder
import com.debugtools.startup.store.StartupStore
import java.io.File
import java.util.UUID

/**
 * Process-wide entry point. Host calls [init] early in Application.onCreate, then
 * reports steps via [begin]/[success]/[fail]/[track] and marks the end with [complete].
 * If [complete] is never called, the first Activity's onResume finalizes the session
 * as a safety net so data is still persisted.
 *
 * Recording works even if [init] was not called yet (a recorder is lazily created);
 * persistence and the lifecycle fallback only activate after [init].
 */
object AppStartupMonitor {

    private const val FALLBACK_DELAY_MS = 1000L

    private val lock = Any()
    private var recorder: StartupRecorder? = null
    private var store: StartupStore? = null
    @Volatile private var persisted = false

    private fun recorder(): StartupRecorder = synchronized(lock) {
        recorder ?: StartupRecorder(
            sessionId = UUID.randomUUID().toString(),
            launchUptimeMs = SystemClock.uptimeMillis(),
            startedAtWallMs = System.currentTimeMillis(),
            appVersion = null,
            clock = { SystemClock.uptimeMillis() }
        ).also { recorder = it }
    }

    /** Wire persistence + the onResume fallback. Idempotent. */
    fun init(context: Context, appVersion: String? = null) {
        synchronized(lock) {
            if (store != null) return
            if (recorder == null) {
                recorder = StartupRecorder(
                    sessionId = UUID.randomUUID().toString(),
                    launchUptimeMs = Process.getStartUptimeMillis(),
                    startedAtWallMs = System.currentTimeMillis(),
                    appVersion = appVersion,
                    clock = { SystemClock.uptimeMillis() }
                )
            }
            store = StartupStore(File(context.applicationContext.filesDir, "startup"))
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(fallback)
    }

    fun begin(name: String, dependsOn: List<String> = emptyList()) = recorder().begin(name, dependsOn)
    fun success(name: String) = recorder().success(name)
    fun fail(name: String, error: Throwable) =
        recorder().fail(name, "${error.javaClass.simpleName}: ${error.message ?: "(no message)"}")
    fun fail(name: String, errorMessage: String) = recorder().fail(name, errorMessage)

    inline fun <T> track(name: String, dependsOn: List<String> = emptyList(), block: () -> T): T {
        begin(name, dependsOn)
        return try {
            val r = block(); success(name); r
        } catch (e: Throwable) {
            fail(name, e); throw e
        }
    }

    /** Marks "startup complete": finalize + persist. */
    fun complete() {
        recorder().complete()
        persist()
    }

    /** Same-process accessor for the module to show the current (possibly in-flight) session. */
    fun currentSession(): StartupSession? = synchronized(lock) { recorder?.snapshot() }

    private fun persist() {
        val r: StartupRecorder
        val s: StartupStore
        synchronized(lock) {
            if (persisted) return
            r = recorder ?: return
            s = store ?: return
            persisted = true
        }
        s.save(r.snapshot())
    }

    private val fallback = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            Handler(Looper.getMainLooper()).postDelayed({
                val r = synchronized(lock) { recorder }
                if (r != null && !r.isCompleted()) r.finalizeFallback()
                persist()
            }, FALLBACK_DELAY_MS)
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :debugtools-startup:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add debugtools-startup/src/main/kotlin/com/debugtools/startup/AppStartupMonitor.kt
git commit -m "feat(startup): add AppStartupMonitor process singleton with lifecycle fallback"
```

---

## Task 7: 视图（配色 + 甘特 + DAG + 会话列表 + 容器）

**Files:**
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/view/StartupColors.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/view/GanttView.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/view/DagView.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/view/SessionListView.kt`
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/view/StartupRootView.kt`

> Android 视图,纯绘制,无单测;编译验证。

- [ ] **Step 1: StartupColors.kt**

```kotlin
package com.debugtools.startup.view

import com.debugtools.startup.protocol.StepStatus

/** Palette for the startup panel. Raw ARGB ints (no android.graphics.Color). */
object StartupColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val ACCENT = 0xFF2DD4BF.toInt()
    val SUCCESS = 0xFF48BB78.toInt()
    val FAILED = 0xFFF43F5E.toInt()
    val RUNNING = 0xFF64748B.toInt()
    val CRITICAL = 0xFFF6AD55.toInt()
    val EDGE = 0xFF4A5568.toInt()

    fun statusColor(s: StepStatus): Int = when (s) {
        StepStatus.SUCCESS -> SUCCESS
        StepStatus.FAILED -> FAILED
        StepStatus.RUNNING -> RUNNING
    }
}
```

- [ ] **Step 2: GanttView.kt**

```kotlin
package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.debugtools.startup.protocol.StartupSession

/** Time-axis Gantt: one row per step, bar start->end colored by status; critical path bars outlined. */
@SuppressLint("ViewConstructor")
class GanttView(context: Context, private val session: StartupSession, private val critical: Set<String>) : View(context) {

    private val density = resources.displayMetrics.density
    private val rowH = 26f * density
    private val labelW = 90f * density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = StartupColors.CRITICAL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StartupColors.TEXT; textSize = 11f * density }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StartupColors.TEXT_DIM; textSize = 9f * density }

    private val t0 = session.launchUptimeMs
    private val totalMs = ((session.completedUptimeMs ?: session.steps.mapNotNull { it.endUptimeMs }.maxOrNull()
        ?: session.launchUptimeMs) - t0).coerceAtLeast(1L)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (session.steps.size * rowH + 22f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val chartW = width - labelW
        if (chartW <= 0) return
        fun x(ms: Long) = labelW + chartW * (ms - t0).coerceIn(0, totalMs) / totalMs.toFloat()

        session.steps.forEachIndexed { i, s ->
            val top = i * rowH + 2f * density
            canvas.drawText(s.name.take(10), 0f, top + rowH * 0.6f, label)
            val end = s.endUptimeMs ?: (t0 + totalMs)
            val r = RectF(x(s.startUptimeMs), top, x(end).coerceAtLeast(x(s.startUptimeMs) + 2f), top + rowH - 6f * density)
            bar.color = StartupColors.statusColor(s.status)
            canvas.drawRoundRect(r, 3f * density, 3f * density, bar)
            if (s.name in critical) canvas.drawRoundRect(r, 3f * density, 3f * density, outline)
        }
        // axis labels: 0 and total
        val baseY = session.steps.size * rowH + 14f * density
        canvas.drawText("0ms", labelW, baseY, axis)
        canvas.drawText("${totalMs}ms", (width - 40f * density), baseY, axis)
    }
}
```

- [ ] **Step 3: DagView.kt**

```kotlin
package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.debugtools.startup.protocol.StartupSession

/** Dependency DAG: nodes laid out in dependency layers, edges = dependsOn, node color = status. */
@SuppressLint("ViewConstructor")
class DagView(context: Context, private val session: StartupSession) : View(context) {

    private val density = resources.displayMetrics.density
    private val node = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = StartupColors.EDGE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = StartupColors.TEXT; textSize = 10f * density }

    private val byName = session.steps.associateBy { it.name }
    private val layer = HashMap<String, Int>()
    private val centers = HashMap<String, Pair<Float, Float>>()
    private val maxLayer: Int

    init {
        // layer = longest dependency depth
        fun depth(name: String, seen: MutableSet<String>): Int {
            if (!seen.add(name)) return 0
            val deps = byName[name]?.dependsOn?.filter { byName.containsKey(it) } ?: emptyList()
            return if (deps.isEmpty()) 0 else 1 + (deps.maxOf { depth(it, seen) })
        }
        session.steps.forEach { layer[it.name] = depth(it.name, hashSetOf()) }
        maxLayer = (layer.values.maxOrNull() ?: 0)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), ((maxLayer + 1) * 50f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val byLayer = session.steps.groupBy { layer[it.name] ?: 0 }
        centers.clear()
        byLayer.forEach { (ly, steps) ->
            val rowY = ly * 50f * density + 24f * density
            steps.forEachIndexed { idx, s ->
                val cx = width * (idx + 1f) / (steps.size + 1f)
                centers[s.name] = cx to rowY
            }
        }
        // edges first
        session.steps.forEach { s ->
            val to = centers[s.name] ?: return@forEach
            s.dependsOn.forEach { d -> centers[d]?.let { from -> canvas.drawLine(from.first, from.second, to.first, to.second, edge) } }
        }
        // nodes
        session.steps.forEach { s ->
            val c = centers[s.name] ?: return@forEach
            node.color = StartupColors.statusColor(s.status)
            val r = 8f * density
            canvas.drawRoundRect(RectF(c.first - 30f * density, c.second - r, c.first + 30f * density, c.second + r), r, r, node)
            canvas.drawText(s.name.take(7), c.first - 28f * density, c.second + 4f * density, text)
        }
    }
}
```

- [ ] **Step 4: SessionListView.kt + StartupRootView.kt**

`SessionListView.kt`:
```kotlin
package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StepStatus

/** Vertical list of sessions; tap a row -> onPick. */
@SuppressLint("ViewConstructor")
class SessionListView(
    context: Context,
    sessions: List<StartupSession>,
    onPick: (StartupSession) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        if (sessions.isEmpty()) {
            addView(TextView(context).apply {
                text = "暂无启动记录。请在宿主 Application.onCreate 调用 AppStartupMonitor 上报。"
                setTextColor(StartupColors.TEXT_DIM); textSize = 12f
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            })
        }
        sessions.forEach { s ->
            val ok = s.steps.count { it.status == StepStatus.SUCCESS }
            val fail = s.steps.count { it.status == StepStatus.FAILED }
            val totalMs = (s.completedUptimeMs ?: s.steps.mapNotNull { it.endUptimeMs }.maxOrNull() ?: s.launchUptimeMs) - s.launchUptimeMs
            addView(TextView(context).apply {
                text = "启动 @${s.startedAtWallMs} · ${totalMs}ms · ✓$ok ✗$fail" +
                    if (!s.completedExplicitly) " · (未显式完成)" else ""
                setTextColor(StartupColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StartupColors.SURFACE) }
                setOnClickListener { onPick(s) }
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * density).toInt() })
        }
    }
}
```

`StartupRootView.kt`:
```kotlin
package com.debugtools.startup.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.startup.analyzer.CriticalPath
import com.debugtools.startup.analyzer.StartupAnalyzer
import com.debugtools.startup.protocol.StartupSession

/** Scrollable panel: session list -> tap -> session detail (summary + Gantt/DAG toggle + issues). */
@SuppressLint("ViewConstructor")
class StartupRootView(
    context: Context,
    private val sessions: List<StartupSession>
) : ScrollView(context), View.OnClickListener {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(12), p(12), p(12)) }
    private var showingDag = false
    private var picked: StartupSession? = null

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(StartupColors.BG)
        addView(content)
        showList()
    }

    private fun showList() {
        picked = null
        content.removeAllViews()
        content.addView(header("启动链路 · 最近 ${sessions.size} 次"))
        content.addView(SessionListView(context, sessions) { showDetail(it) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun showDetail(s: StartupSession) {
        picked = s
        content.removeAllViews()
        content.addView(backBtn())
        val crit = CriticalPath.of(s)
        val totalMs = (s.completedUptimeMs ?: s.launchUptimeMs) - s.launchUptimeMs
        content.addView(header("总耗时 ${totalMs}ms · 关键路径: ${crit.joinToString("→")}"))
        content.addView(toggleBtn())
        if (showingDag) content.addView(DagView(context, s), lp())
        else content.addView(GanttView(context, s, crit.toSet()), lp())
        // issues
        content.addView(header("⚠ 诊断"))
        val issues = StartupAnalyzer.analyze(s)
        if (issues.isEmpty()) content.addView(dim("无异常"))
        else issues.forEach { content.addView(dim("• [${it.type}] ${it.stepName ?: ""} ${it.detail}")) }
    }

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) }

    private fun header(t: String) = TextView(context).apply {
        text = t; setTextColor(StartupColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }

    private fun dim(t: String) = TextView(context).apply {
        text = t; setTextColor(StartupColors.TEXT_DIM); textSize = 11f; setPadding(0, p(2), 0, p(2))
    }

    private fun toggleBtn() = TextView(context).apply {
        text = if (showingDag) "切到甘特图" else "切到依赖图"
        setTextColor(StartupColors.TEXT); textSize = 12f; gravity = Gravity.CENTER; setPadding(0, p(8), 0, p(8))
        background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(StartupColors.ACCENT) }
        setOnClickListener { showingDag = !showingDag; picked?.let { showDetail(it) } }
    }

    private fun backBtn() = TextView(context).apply {
        text = "← 返回列表"; setTextColor(StartupColors.ACCENT); textSize = 12f; setPadding(0, p(4), 0, p(8))
        setOnClickListener { showingDag = false; showList() }
    }

    override fun onClick(v: View?) {}
}
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :debugtools-startup:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add debugtools-startup/src/main/kotlin/com/debugtools/startup/view/
git commit -m "feat(startup): add gantt, DAG, session-list and root views"
```

---

## Task 8: StartupMonitorModule（DebugModule 入口）+ 整模块构建

**Files:**
- Create: `debugtools-startup/src/main/kotlin/com/debugtools/startup/StartupMonitorModule.kt`

- [ ] **Step 1: 读 DebugModule 接口确认签名**

打开 `debugtools-core/src/main/kotlin/com/debugtools/core/module/DebugModule.kt` 确认要实现的成员(`moduleId`、`tabTitle`、`onAttach(context, storage)`、`onDetach()`、`createContentView(context)`、`buildSettings()`、`getBriefItems()`),与 audiomon 的 `AudioMonitorModule` 写法一致。

- [ ] **Step 2: 实现 StartupMonitorModule**

```kotlin
package com.debugtools.startup

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.store.StartupStore
import com.debugtools.startup.view.StartupRootView
import java.io.File

/**
 * Debug module that shows captured app-startup sessions (persisted + the current one).
 *
 * The host reports via [AppStartupMonitor] from Application.onCreate; this module just
 * reads and visualizes. Register it like any other module:
 * ```kotlin
 * DebugTools.builder(context).register(StartupMonitorModule()).build()
 * ```
 */
class StartupMonitorModule : DebugModule {

    override val moduleId: String = "startup"
    override val tabTitle: String = "启动链路"

    private var appContext: Context? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        appContext = context.applicationContext
    }

    override fun onDetach() { appContext = null }

    override fun createContentView(context: Context): View {
        val store = StartupStore(File(context.applicationContext.filesDir, "startup"))
        val persisted = store.load()
        // Merge the in-flight current session (same process) on top, de-duplicated by id.
        val current = AppStartupMonitor.currentSession()
        val sessions = mergeCurrent(current, persisted)
        return StartupRootView(context, sessions)
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun getBriefItems(): List<BriefItem> {
        val current = AppStartupMonitor.currentSession() ?: return emptyList()
        val fail = current.steps.count { it.status.name == "FAILED" }
        return listOf(BriefItem(text = "启动 ${current.steps.size}步" + if (fail > 0) " · ${fail}失败" else ""))
    }

    private fun mergeCurrent(current: StartupSession?, persisted: List<StartupSession>): List<StartupSession> {
        if (current == null) return persisted
        val rest = persisted.filter { it.sessionId != current.sessionId }
        return listOf(current) + rest
    }
}
```

- [ ] **Step 3: 整模块构建 + 全量测试**

Run: `./gradlew :debugtools-startup:assembleDebug :debugtools-startup:test`
Expected: BUILD SUCCESSFUL;协议/recorder/store/analyzer/criticalpath 测试全过。

- [ ] **Step 4: 提交**

```bash
git add debugtools-startup/src/main/kotlin/com/debugtools/startup/StartupMonitorModule.kt
git commit -m "feat(startup): add StartupMonitorModule entry point"
```

---

## Task 9: 在 sample app 接入并产生一次启动会话

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/debugtools/sample/SampleApplication.kt`
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`

- [ ] **Step 1: app 依赖加入模块**

在 `app/build.gradle.kts` 的 dependencies 里,`implementation(project(":debugtools-audiomon"))` 之后加:
```kotlin
    implementation(project(":debugtools-startup"))
```

- [ ] **Step 2: 在 Application.onCreate 模拟启动链路上报**

把 `SampleApplication.kt` 整体替换为(用 track + 模拟若干带依赖、含一个失败、含一个慢组件的初始化):
```kotlin
package com.debugtools.sample

import android.app.Application
import com.debugtools.startup.AppStartupMonitor

class SampleApplication : Application() {
    // DebugTools 在 MainActivity 拿到悬浮窗权限后初始化,保存引用供全局访问
    var voiceModule: VoiceAssistantModule? = null

    override fun onCreate() {
        super.onCreate()
        AppStartupMonitor.init(this, appVersion = "1.0")

        // 模拟一条带依赖的启动链路:config -> (asr, nlu) ; net 并行; tts 依赖 asr。
        AppStartupMonitor.track("config") { Thread.sleep(20) }
        AppStartupMonitor.track("net") { Thread.sleep(15) }                 // 无依赖、并行
        AppStartupMonitor.track("asr", listOf("config")) { Thread.sleep(70) } // 慢组件(>50ms)
        try {
            AppStartupMonitor.track("nlu", listOf("config")) {
                Thread.sleep(10); throw IllegalStateException("模型文件缺失")  // 失败也算结束
            }
        } catch (_: Exception) { /* 已记录为 FAILED */ }
        AppStartupMonitor.track("tts", listOf("asr")) { Thread.sleep(25) }
        AppStartupMonitor.complete()
    }
}
```

- [ ] **Step 3: 注册 StartupMonitorModule**

在 `MainActivity.kt` 顶部 import 加:
```kotlin
import com.debugtools.startup.StartupMonitorModule
```
在 `initDebugTools()` 的 builder 链里,`.register(audioModule)` 之后加一行:
```kotlin
                .register(StartupMonitorModule())
```

- [ ] **Step 4: 构建 + 运行验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。安装后打开 DebugTools「启动链路」Tab → 看到一条会话 → 点开:甘特图(config/net 并行,asr 慢,nlu 红色失败,tts 依赖 asr),诊断列出 SLOW(asr)、ERROR(nlu),关键路径 config→asr→tts。

- [ ] **Step 5: 提交**

```bash
git add app/build.gradle.kts \
  app/src/main/kotlin/com/debugtools/sample/SampleApplication.kt \
  app/src/main/kotlin/com/debugtools/sample/MainActivity.kt
git commit -m "feat(app): demo startup-chain monitoring with a simulated init flow"
```

---

## Task 10: README

**Files:**
- Create: `debugtools-startup/README.md`

- [ ] **Step 1: 写 README**

```markdown
# debugtools-startup

App 启动链路监控:接入方按协议上报每个组件的初始化(begin/success/fail + 依赖),SDK 记录、持久化(最近 10 次,App 私有目录卸载即删)、判定成功/失败/流程/耗时,提供甘特图 + 依赖图双视图与自动诊断。

## 接入(3 步)

1) Application.onCreate 尽早 init:
\`\`\`kotlin
AppStartupMonitor.init(this, appVersion = "1.0")
\`\`\`

2) 每个组件初始化处上报(track 同步糖,或 begin/success/fail 手动):
\`\`\`kotlin
AppStartupMonitor.track("asr", dependsOn = listOf("config")) { initAsr() }
// 异步/回调式:
AppStartupMonitor.begin("net")
onNetReady { AppStartupMonitor.success("net") }     // 或 fail(name, throwable)
\`\`\`

3) 启动完成处标记:
\`\`\`kotlin
AppStartupMonitor.complete()   // 漏调有首个 Activity onResume 兜底
\`\`\`

注册模块即可在悬浮窗「启动链路」Tab 查看:
\`\`\`kotlin
DebugTools.builder(context).register(StartupMonitorModule()).build()
\`\`\`

## 自动诊断
报错 / 慢组件(>50ms) / 依赖倒挂(在依赖完成前就开始) / 卡死(漏 end) / 依赖环 / 可并行却串行;并算关键路径。

## 约束
仅同进程上报;数据存 `filesDir/startup`,保留最近 10 次,卸载随 App 删除;不做网络上报。
```

- [ ] **Step 2: 提交**

```bash
git add debugtools-startup/README.md
git commit -m "docs(startup): add module README with integration guide"
```

---

## 验收清单

- [ ] `./gradlew :debugtools-startup:test` 全绿(JSON / recorder / store / analyzer / criticalpath)
- [ ] `:debugtools-startup:assembleDebug` 与 `:app:assembleDebug` 通过
- [ ] 协议三件套 + track + complete + 兜底 行为符合 spec
- [ ] 持久化最近 10 次,App 私有目录
- [ ] 「启动链路」Tab:会话列表 → 甘特/依赖图可切 → 诊断 + 关键路径
- [ ] demo 在 Application 产生一条含 并行/慢/失败/依赖 的会话,可见
