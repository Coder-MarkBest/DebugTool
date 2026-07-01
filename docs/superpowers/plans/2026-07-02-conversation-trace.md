# Conversation Trace (debugtools-conversation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 `debugtools-conversation` 模块:接入方写适配层把现有对话链路日志映射到通用协议 → `submitTurn(turn)` 提交,SDK 记录、持久化(最近 50 次对话)、提供三层导航(会话列表→轮次列表→轮次详情含阶段时间线)+ 自动诊断。

**Architecture:** 纯逻辑下沉:协议数据类(JSON 序列化)、`ConversationRecorder`(线程安全累积一个 session 的 turns)、`ConversationStore`(落盘/LRU 50)、`TurnAnalyzer`(5 条诊断规则)全部纯 JVM 可测;`ConversationTracer`(进程单例)是 Android 粘合(生命周期兜底、落盘);`ConversationMonitorModule` + 三层视图是展示层。与 `debugtools-startup` 架构完全一致。

**Tech Stack:** Kotlin, Android(compileSdk 34, minSdk 26), Android 内置 `org.json`(生产)+ `org.json:json`(测试), JUnit4, Android Canvas。

**参考规格:** `docs/superpowers/specs/2026-07-02-conversation-trace-design.md`

---

## File Structure

新增模块 `debugtools-conversation/`:
- `protocol/StageStatus.kt`、`protocol/TurnStage.kt`、`protocol/TurnOutcome.kt`、`protocol/ConversationTurn.kt`、`protocol/ConversationSession.kt`、`protocol/TurnIssue.kt` — 协议 + JSON
- `recorder/ConversationRecorder.kt` — 纯逻辑:一个 session 内 turns 累积器
- `store/ConversationStore.kt` — 落盘 / 读取 / LRU 50
- `analyzer/TurnAnalyzer.kt` — 纯诊断(5 规则)
- `ConversationTracer.kt` — 进程单例(Android 粘合:startSession/submitTurn/endSession/兜底)
- `view/ConversationColors.kt`、`view/SessionListView.kt`、`view/TurnListView.kt`、`view/TurnDetailView.kt`、`view/ConversationRootView.kt`
- `ConversationMonitorModule.kt` — DebugModule 入口
- 测试:`ConversationSessionJsonTest`、`ConversationRecorderTest`、`ConversationStoreTest`、`TurnAnalyzerTest`
- `build.gradle.kts`、`src/main/AndroidManifest.xml`

修改:`settings.gradle.kts`(include)、`app/`(demo 接入)。

---

## Task 1: 模块骨架(gradle + manifest + settings)

**Files:**
- Modify: `settings.gradle.kts`
- Create: `debugtools-conversation/build.gradle.kts`
- Create: `debugtools-conversation/src/main/AndroidManifest.xml`

- [ ] **Step 1: settings.gradle.kts 加入模块**

在 `include(":debugtools-startup")` 之后加一行:
```kotlin
include(":debugtools-conversation")
```

- [ ] **Step 2: 创建 build.gradle.kts**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.conversation"
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
    testImplementation("org.json:json:20240303")
}
```

- [ ] **Step 3: 创建 AndroidManifest.xml**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: 验证构建**

Run: `./gradlew :debugtools-conversation:assembleDebug`
Expected: BUILD SUCCESSFUL（空模块）。

- [ ] **Step 5: 提交**

```bash
git add settings.gradle.kts debugtools-conversation/build.gradle.kts debugtools-conversation/src/main/AndroidManifest.xml
git commit -m "chore(conversation): scaffold debugtools-conversation module"
```

---

## Task 2: 协议数据类 + JSON 序列化

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/protocol/StageStatus.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/protocol/TurnStage.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/protocol/TurnOutcome.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/protocol/ConversationTurn.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/protocol/ConversationSession.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/protocol/TurnIssue.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/protocol/ConversationSessionJsonTest.kt`

- [ ] **Step 1: 写失败测试**

`ConversationSessionJsonTest.kt`:
```kotlin
package com.debugtools.conversation.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationSessionJsonTest {

    private fun sample() = ConversationSession(
        sessionId = "s1",
        startedAtWallMs = 1000L,
        metadata = mapOf("scene" to "导航中"),
        turns = listOf(
            ConversationTurn(
                turnId = "t1", turnIndex = 1, sessionId = "s1",
                startUptimeMs = 1100L, endUptimeMs = 1500L,
                userInput = "导航到最近的加油站",
                stages = listOf(
                    TurnStage("唤醒", 0, 200, StageStatus.SUCCESS, null, null, null, "main"),
                    TurnStage("ASR", 200, 600, StageStatus.SUCCESS, "[audio]", "导航到最近的加油站", null, "asr-1"),
                    TurnStage("NLU", 600, 650, StageStatus.SUCCESS, "导航到最近的加油站", """{"intent":"导航","slots":{"dest":"加油站"}}""", null, "nlu-1"),
                    TurnStage("TTS", 650, 900, StageStatus.SUCCESS, null, "已为您找到最近的加油站", null, "tts-1"),
                    TurnStage("执行", 900, null, StageStatus.FAILED, null, null, "NavigationService timeout", "exec-1")
                ),
                outcome = TurnOutcome.FAILED,
                tags = listOf("导航")
            )
        ),
        endedAtWallMs = 2000L
    )

    @Test fun `session round-trips through json`() {
        val json = sample().toJson()
        val back = ConversationSession.fromJson(JSONObject(json.toString()))
        assertEquals(sample(), back)
    }

    @Test fun `nulls survive round-trip`() {
        val back = ConversationSession.fromJson(JSONObject(sample().toJson().toString()))
        val failed = back.turns.single().stages.first { it.name == "执行" }
        assertNull(failed.endOffsetMs)
        assertNull(failed.input)
        assertNull(failed.output)
        assertEquals(StageStatus.FAILED, failed.status)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-conversation:test`
Expected: FAIL（类型未定义）。

- [ ] **Step 3: 实现 6 个协议文件**

`StageStatus.kt`:
```kotlin
package com.debugtools.conversation.protocol

enum class StageStatus { RUNNING, SUCCESS, FAILED, SKIPPED }
```

`TurnOutcome.kt`:
```kotlin
package com.debugtools.conversation.protocol

enum class TurnOutcome { SUCCESS, FAILED, ABORTED, TIMEOUT }
```

`TurnStage.kt`:
```kotlin
package com.debugtools.conversation.protocol

import org.json.JSONObject

data class TurnStage(
    val name: String,
    val startOffsetMs: Long,
    val endOffsetMs: Long?,
    val status: StageStatus,
    val input: String?,
    val output: String?,
    val error: String?,
    val thread: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("startOffsetMs", startOffsetMs)
        put("endOffsetMs", endOffsetMs ?: JSONObject.NULL)
        put("status", status.name)
        put("input", input ?: JSONObject.NULL)
        put("output", output ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("thread", thread)
    }

    companion object {
        fun fromJson(o: JSONObject): TurnStage = TurnStage(
            name = o.getString("name"),
            startOffsetMs = o.getLong("startOffsetMs"),
            endOffsetMs = if (o.isNull("endOffsetMs")) null else o.getLong("endOffsetMs"),
            status = StageStatus.valueOf(o.getString("status")),
            input = if (o.isNull("input")) null else o.getString("input"),
            output = if (o.isNull("output")) null else o.getString("output"),
            error = if (o.isNull("error")) null else o.getString("error"),
            thread = o.getString("thread")
        )
    }
}
```

`ConversationTurn.kt`:
```kotlin
package com.debugtools.conversation.protocol

import org.json.JSONArray
import org.json.JSONObject

data class ConversationTurn(
    val turnId: String,
    val turnIndex: Int,
    val sessionId: String,
    val startUptimeMs: Long,
    val endUptimeMs: Long?,
    val userInput: String?,
    val stages: List<TurnStage>,
    val outcome: TurnOutcome,
    val tags: List<String>?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("turnId", turnId)
        put("turnIndex", turnIndex)
        put("sessionId", sessionId)
        put("startUptimeMs", startUptimeMs)
        put("endUptimeMs", endUptimeMs ?: JSONObject.NULL)
        put("userInput", userInput ?: JSONObject.NULL)
        put("stages", JSONArray().apply { stages.forEach { put(it.toJson()) } })
        put("outcome", outcome.name)
        put("tags", if (tags != null) JSONArray(tags) else JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): ConversationTurn {
            val stagesArr = o.getJSONArray("stages")
            val stages = (0 until stagesArr.length()).map { TurnStage.fromJson(stagesArr.getJSONObject(it)) }
            val tagsArr = o.optJSONArray("tags")
            val tags = if (tagsArr != null && !o.isNull("tags")) {
                (0 until tagsArr.length()).map { tagsArr.getString(it) }
            } else null
            return ConversationTurn(
                turnId = o.getString("turnId"),
                turnIndex = o.getInt("turnIndex"),
                sessionId = o.getString("sessionId"),
                startUptimeMs = o.getLong("startUptimeMs"),
                endUptimeMs = if (o.isNull("endUptimeMs")) null else o.getLong("endUptimeMs"),
                userInput = if (o.isNull("userInput")) null else o.getString("userInput"),
                stages = stages,
                outcome = TurnOutcome.valueOf(o.getString("outcome")),
                tags = tags
            )
        }
    }
}
```

`ConversationSession.kt`:
```kotlin
package com.debugtools.conversation.protocol

import org.json.JSONArray
import org.json.JSONObject

data class ConversationSession(
    val sessionId: String,
    val startedAtWallMs: Long,
    val metadata: Map<String, String>?,
    val turns: List<ConversationTurn>,
    val endedAtWallMs: Long?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("startedAtWallMs", startedAtWallMs)
        put("metadata", if (metadata != null) JSONObject(metadata) else JSONObject.NULL)
        put("turns", JSONArray().apply { turns.forEach { put(it.toJson()) } })
        put("endedAtWallMs", endedAtWallMs ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): ConversationSession {
            val turnsArr = o.getJSONArray("turns")
            val turns = (0 until turnsArr.length()).map { ConversationTurn.fromJson(turnsArr.getJSONObject(it)) }
            val metaObj = o.optJSONObject("metadata")
            val metadata = if (metaObj != null && !o.isNull("metadata")) {
                metaObj.keys().asSequence().associateWith { metaObj.getString(it) }
            } else null
            return ConversationSession(
                sessionId = o.getString("sessionId"),
                startedAtWallMs = o.getLong("startedAtWallMs"),
                metadata = metadata,
                turns = turns,
                endedAtWallMs = if (o.isNull("endedAtWallMs")) null else o.getLong("endedAtWallMs")
            )
        }
    }
}
```

`TurnIssue.kt`:
```kotlin
package com.debugtools.conversation.protocol

/** A problem the analyzer found in a turn. */
data class TurnIssue(
    val type: TurnIssueType,
    val stageName: String?,
    val detail: String,
    val severity: TurnIssueSeverity
)

enum class TurnIssueType { STAGE_FAILED, SLOW_STAGE, TURN_TIMEOUT, TURN_ABORTED, PIPELINE_GAP }
enum class TurnIssueSeverity { ERROR, WARN, INFO }
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-conversation:test`
Expected: PASS（2 个测试）。

- [ ] **Step 5: 提交**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/protocol/ \
  debugtools-conversation/src/test/kotlin/com/debugtools/conversation/protocol/ConversationSessionJsonTest.kt
git commit -m "feat(conversation): add conversation protocol data classes with JSON serialization"
```

---

## Task 3: ConversationRecorder（线程安全会话累积器，纯逻辑）

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/recorder/ConversationRecorder.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/recorder/ConversationRecorderTest.kt`

- [ ] **Step 1: 写失败测试**

`ConversationRecorderTest.kt`:
```kotlin
package com.debugtools.conversation.recorder

import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnOutcome
import com.debugtools.conversation.protocol.TurnStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRecorderTest {

    private class Clock(var now: Long = 0L) { fun read() = now }

    private fun recorder(clock: Clock) = ConversationRecorder(
        sessionId = "s1", startedAtWallMs = 1000L, clock = clock::read
    )

    private fun turn(id: String, idx: Int, start: Long, end: Long?, outcome: TurnOutcome, stages: List<TurnStage> = emptyList()) =
        ConversationTurn(turnId = id, turnIndex = idx, sessionId = "s1",
            startUptimeMs = start, endUptimeMs = end, userInput = null,
            stages = stages, outcome = outcome, tags = null)

    @Test fun `submitTurn appends to session and keeps index order`() {
        val c = Clock(); c.now = 100; val r = recorder(c)
        r.submitTurn(turn("t1", 1, 100, 200, TurnOutcome.SUCCESS))
        r.submitTurn(turn("t2", 2, 250, 400, TurnOutcome.SUCCESS))
        val s = r.snapshot()
        assertEquals(2, s.turns.size)
        assertEquals(listOf("t1", "t2"), s.turns.map { it.turnId })
    }

    @Test fun `endSession sets endedAtWallMs`() {
        val c = Clock(); c.now = 500; val r = recorder(c)
        r.submitTurn(turn("t1", 1, 100, 200, TurnOutcome.SUCCESS))
        c.now = 600; r.endSession()
        assertEquals(600L, r.snapshot().endedAtWallMs)
    }

    @Test fun `endSession is idempotent`() {
        val c = Clock(); val r = recorder(c)
        c.now = 10; r.endSession()
        c.now = 20; r.endSession()
        assertEquals(10L, r.snapshot().endedAtWallMs)
    }

    @Test fun `isEnded returns false until endSession`() {
        val r = recorder(Clock())
        assertEquals(false, r.isEnded())
        r.endSession()
        assertEquals(true, r.isEnded())
    }

    @Test fun `snapshot returns null sessionId when no turns`() {
        val s = recorder(Clock()).snapshot()
        assertEquals("s1", s.sessionId)
        assertEquals(0, s.turns.size)
    }

    @Test fun `startSession updates metadata`() {
        val r = recorder(Clock())
        r.startSession(mapOf("scene" to "导航"))
        assertEquals(mapOf("scene" to "导航"), r.snapshot().metadata)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-conversation:test`
Expected: FAIL（ConversationRecorder 未定义）。

- [ ] **Step 3: 实现 ConversationRecorder**

```kotlin
package com.debugtools.conversation.recorder

import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn

/**
 * Accumulates turns for one conversation session. Thread-safe.
 * Pure JVM — [clock] is injectable for unit testing.
 */
class ConversationRecorder(
    private val sessionId: String,
    private val startedAtWallMs: Long,
    private var metadata: Map<String, String>? = null,
    private val clock: () -> Long
) {
    private val lock = Any()
    private val turns = mutableListOf<ConversationTurn>()
    private var endedAtWallMs: Long? = null

    fun startSession(meta: Map<String, String>?) = synchronized(lock) {
        if (metadata == null) metadata = meta
    }

    fun submitTurn(turn: ConversationTurn) = synchronized(lock) {
        turns.add(turn)
    }

    fun endSession() = synchronized(lock) {
        if (endedAtWallMs == null) { endedAtWallMs = clock() }
    }

    /**
     * Safety-net: if host never calls endSession(), finalizes with wall-clock time
     * derived from the last turn's endUptimeMs (approximate fallback). Never overrides
     * an explicit endSession().
     */
    fun finalizeFallback() = synchronized(lock) {
        if (endedAtWallMs == null) { endedAtWallMs = clock() }
    }

    fun isEnded(): Boolean = synchronized(lock) { endedAtWallMs != null }

    fun snapshot(): ConversationSession = synchronized(lock) {
        ConversationSession(
            sessionId = sessionId,
            startedAtWallMs = startedAtWallMs,
            metadata = metadata,
            turns = turns.toList(),
            endedAtWallMs = endedAtWallMs
        )
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-conversation:test`
Expected: PASS（6 个 recorder 测试 + 2 个协议测试）。

- [ ] **Step 5: 提交**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/recorder/ConversationRecorder.kt \
  debugtools-conversation/src/test/kotlin/com/debugtools/conversation/recorder/ConversationRecorderTest.kt
git commit -m "feat(conversation): add thread-safe ConversationRecorder"
```

---

## Task 4: ConversationStore（落盘 / 读取 / LRU 50）

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/store/ConversationStore.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/store/ConversationStoreTest.kt`

- [ ] **Step 1: 写失败测试**

`ConversationStoreTest.kt`:
```kotlin
package com.debugtools.conversation.store

import com.debugtools.conversation.protocol.ConversationSession
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun session(id: String, wall: Long) = ConversationSession(
        sessionId = id, startedAtWallMs = wall, metadata = null,
        turns = emptyList(), endedAtWallMs = wall + 1000L
    )

    @Test fun `save then load returns the session`() {
        val store = ConversationStore(tmp.root)
        store.save(session("a", 100L))
        val loaded = store.load()
        assertEquals(1, loaded.size)
        assertEquals("a", loaded.first().sessionId)
    }

    @Test fun `load returns most-recent first`() {
        val store = ConversationStore(tmp.root)
        store.save(session("old", 100L))
        store.save(session("new", 200L))
        assertEquals(listOf("new", "old"), store.load().map { it.sessionId })
    }

    @Test fun `keeps only the most recent maxSessions`() {
        val store = ConversationStore(tmp.root, maxSessions = 3)
        (1..5).forEach { store.save(session("s$it", it * 100L)) }
        assertEquals(listOf("s5", "s4", "s3"), store.load().map { it.sessionId })
    }

    @Test fun `corrupt file is skipped, others still load`() {
        val store = ConversationStore(tmp.root)
        store.save(session("good", 100L))
        tmp.root.resolve("050_bad.json").writeText("{ not valid json")
        assertEquals(listOf("good"), store.load().map { it.sessionId })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-conversation:test`
Expected: FAIL。

- [ ] **Step 3: 实现 ConversationStore**

```kotlin
package com.debugtools.conversation.store

import com.debugtools.conversation.protocol.ConversationSession
import org.json.JSONObject
import java.io.File

/**
 * Persists conversation sessions as JSON under [dir] (host passes context.filesDir/conversation,
 * which Android removes on uninstall). Keeps the [maxSessions] most recent.
 */
class ConversationStore(
    private val dir: File,
    private val maxSessions: Int = 50
) {
    fun save(session: ConversationSession) {
        dir.mkdirs()
        val name = "%013d_%s.json".format(session.startedAtWallMs, session.sessionId)
        File(dir, name).writeText(session.toJson().toString())
        evict()
    }

    /** Most-recent first. Unparseable files are skipped. */
    fun load(): List<ConversationSession> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files
            .sortedByDescending { it.name }
            .mapNotNull { runCatching { ConversationSession.fromJson(JSONObject(it.readText())) }.getOrNull() }
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

Run: `./gradlew :debugtools-conversation:test`
Expected: PASS（4 个 store 测试）。

- [ ] **Step 5: 提交**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/store/ConversationStore.kt \
  debugtools-conversation/src/test/kotlin/com/debugtools/conversation/store/ConversationStoreTest.kt
git commit -m "feat(conversation): add ConversationStore with LRU 50 persistence"
```

---

## Task 5: TurnAnalyzer（诊断，5 规则，纯逻辑）

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/analyzer/TurnAnalyzer.kt`
- Test: `debugtools-conversation/src/test/kotlin/com/debugtools/conversation/analyzer/TurnAnalyzerTest.kt`

- [ ] **Step 1: 写失败测试**

`TurnAnalyzerTest.kt`:
```kotlin
package com.debugtools.conversation.analyzer

import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnIssueType
import com.debugtools.conversation.protocol.TurnOutcome
import com.debugtools.conversation.protocol.TurnStage
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnAnalyzerTest {

    private fun stage(name: String, start: Long, end: Long?, status: StageStatus, error: String? = null) =
        TurnStage(name, start, end, status, null, null, error, "t")

    private fun turn(stages: List<TurnStage>, outcome: TurnOutcome = TurnOutcome.SUCCESS) =
        ConversationTurn("t1", 1, "s1", 0L, stages.mapNotNull { it.endOffsetMs }.maxOrNull(),
            null, stages, outcome, null)

    private fun types(t: ConversationTurn) = TurnAnalyzer.analyze(t).map { it.type }

    @Test fun `failed stage is STAGE_FAILED`() {
        val t = turn(listOf(stage("ASR", 0, 100, StageStatus.FAILED, "Engine crash")))
        assertTrue(types(t).contains(TurnIssueType.STAGE_FAILED))
    }

    @Test fun `stage over slow threshold is SLOW_STAGE`() {
        val t = turn(listOf(stage("TTS", 0, 800, StageStatus.SUCCESS))) // 800ms > 500
        assertTrue(types(t).contains(TurnIssueType.SLOW_STAGE))
    }

    @Test fun `fast stage is not SLOW_STAGE`() {
        val t = turn(listOf(stage("NLU", 0, 50, StageStatus.SUCCESS))) // 50ms < 500
        assertTrue(!types(t).contains(TurnIssueType.SLOW_STAGE))
    }

    @Test fun `TIMEOUT outcome is TURN_TIMEOUT`() {
        val t = turn(listOf(stage("ASR", 0, 5000, StageStatus.SUCCESS)), TurnOutcome.TIMEOUT)
        assertTrue(types(t).contains(TurnIssueType.TURN_TIMEOUT))
    }

    @Test fun `ABORTED outcome is TURN_ABORTED`() {
        val t = turn(listOf(stage("ASR", 0, 100, StageStatus.SUCCESS)), TurnOutcome.ABORTED)
        assertTrue(types(t).contains(TurnIssueType.TURN_ABORTED))
    }

    @Test fun `gap between consecutive stages is PIPELINE_GAP`() {
        val t = turn(listOf(
            stage("ASR", 0, 100, StageStatus.SUCCESS),
            stage("NLU", 200, 250, StageStatus.SUCCESS) // 100ms gap after ASR
        ))
        assertTrue(types(t).contains(TurnIssueType.PIPELINE_GAP))
    }

    @Test fun `contiguous stages have no PIPELINE_GAP`() {
        val t = turn(listOf(
            stage("ASR", 0, 100, StageStatus.SUCCESS),
            stage("NLU", 100, 150, StageStatus.SUCCESS) // exactly contiguous
        ))
        assertTrue(!types(t).contains(TurnIssueType.PIPELINE_GAP))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-conversation:test`
Expected: FAIL（TurnAnalyzer 未定义）。

- [ ] **Step 3: 实现 TurnAnalyzer**

```kotlin
package com.debugtools.conversation.analyzer

import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnIssue
import com.debugtools.conversation.protocol.TurnIssueSeverity
import com.debugtools.conversation.protocol.TurnIssueType
import com.debugtools.conversation.protocol.TurnOutcome

/** Pure diagnostics over a completed conversation turn. */
object TurnAnalyzer {

    const val DEFAULT_SLOW_STAGE_MS = 500L

    fun analyze(turn: ConversationTurn, slowThresholdMs: Long = DEFAULT_SLOW_STAGE_MS): List<TurnIssue> {
        val issues = mutableListOf<TurnIssue>()

        for (s in turn.stages) {
            val dur = if (s.endOffsetMs != null) s.endOffsetMs - s.startOffsetMs else null

            if (s.status == StageStatus.FAILED) {
                issues += TurnIssue(TurnIssueType.STAGE_FAILED, s.name,
                    s.error ?: "阶段执行失败", TurnIssueSeverity.ERROR)
            }
            if (dur != null && dur > slowThresholdMs) {
                issues += TurnIssue(TurnIssueType.SLOW_STAGE, s.name,
                    "耗时 ${dur}ms", TurnIssueSeverity.WARN)
            }
        }

        if (turn.outcome == TurnOutcome.TIMEOUT) {
            issues += TurnIssue(TurnIssueType.TURN_TIMEOUT, null,
                "轮次超时，总耗时 ${turn.endUptimeMs?.minus(turn.startUptimeMs) ?: "?"}ms", TurnIssueSeverity.WARN)
        }
        if (turn.outcome == TurnOutcome.ABORTED) {
            issues += TurnIssue(TurnIssueType.TURN_ABORTED, null, "轮次被中断", TurnIssueSeverity.WARN)
        }

        // pipeline gaps: stage[N].startOffsetMs > stage[N-1].endOffsetMs
        turn.stages.zipWithNext { prev, curr ->
            val prevEnd = prev.endOffsetMs ?: return@zipWithNext
            if (curr.startOffsetMs > prevEnd) {
                val gap = curr.startOffsetMs - prevEnd
                issues += TurnIssue(TurnIssueType.PIPELINE_GAP, curr.name,
                    "前一阶段结束到本阶段开始间隔 ${gap}ms，流水线存在空闲", TurnIssueSeverity.INFO)
            }
        }

        return issues
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-conversation:test`
Expected: PASS（7 个 analyzer 测试）。

- [ ] **Step 5: 提交**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/analyzer/TurnAnalyzer.kt \
  debugtools-conversation/src/test/kotlin/com/debugtools/conversation/analyzer/TurnAnalyzerTest.kt
git commit -m "feat(conversation): add TurnAnalyzer diagnostics (5 rules)"
```

---

## Task 6: ConversationTracer（进程单例，Android 粘合）

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationTracer.kt`

> 粘合层：包装 recorder + store，提供 startSession/submitTurn/endSession，生命周期兜底。无单测；编译验证。

- [ ] **Step 1: 实现 ConversationTracer**

```kotlin
package com.debugtools.conversation

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.recorder.ConversationRecorder
import com.debugtools.conversation.store.ConversationStore
import java.io.File
import java.util.UUID

/**
 * Process-wide entry point for conversation trace. The host adapter layer maps
 * its existing conversation logs to [ConversationTurn] and calls [submitTurn].
 *
 * ```kotlin
 * ConversationTracer.init(context)
 * // optional: ConversationTracer.startSession(id, metadata)
 * ConversationTracer.submitTurn(adaptLogToTurn(rawLog))
 * ConversationTracer.endSession(id)
 * ```
 *
 * If [startSession] is not called, the recorder is lazily created on first [submitTurn].
 * If [endSession] is never called, the first Activity's onResume auto-finalizes.
 */
object ConversationTracer {

    private const val FALLBACK_DELAY_MS = 1000L

    private val lock = Any()
    private var recorder: ConversationRecorder? = null
    private var store: ConversationStore? = null
    @Volatile private var persisted = false

    private fun recorder(): ConversationRecorder = synchronized(lock) {
        recorder ?: newRecorder(UUID.randomUUID().toString()).also { recorder = it }
    }

    private fun newRecorder(sessionId: String) = ConversationRecorder(
        sessionId = sessionId,
        startedAtWallMs = System.currentTimeMillis(),
        clock = { SystemClock.uptimeMillis() }
    )

    /** Wire persistence + the onResume fallback. Idempotent. */
    fun init(context: Context) {
        synchronized(lock) {
            if (store != null) return
            store = ConversationStore(File(context.applicationContext.filesDir, "conversation"))
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(fallback)
    }

    fun startSession(sessionId: String, metadata: Map<String, String>? = null) {
        synchronized(lock) {
            if (recorder != null && recorder?.snapshot()?.sessionId == sessionId) {
                recorder?.startSession(metadata)
                return
            }
            // New session: persist the old one if any, then create fresh
            persist()
            recorder = newRecorder(sessionId).also { it.startSession(metadata) }
            persisted = false
        }
    }

    fun submitTurn(turn: ConversationTurn) = recorder().submitTurn(turn)

    fun endSession(sessionId: String) {
        recorder().endSession()
        persist()
    }

    fun currentSession(): ConversationSession? = synchronized(lock) { recorder?.snapshot() }

    fun loadSessions(): List<ConversationSession> {
        val s = synchronized(lock) { store } ?: return emptyList()
        return s.load()
    }

    private fun persist() {
        val r: ConversationRecorder
        val s: ConversationStore
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
            if (persisted) return
            Handler(Looper.getMainLooper()).postDelayed({
                val r = synchronized(lock) { recorder }
                if (r != null && !r.isEnded()) r.finalizeFallback()
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

Run: `./gradlew :debugtools-conversation:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationTracer.kt
git commit -m "feat(conversation): add ConversationTracer process singleton with lifecycle fallback"
```

---

## Task 7: 视图（配色 + 三层导航容器 + 会话列表 + 轮次列表 + 轮次详情含阶段时间线）

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/ConversationColors.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/SessionListView.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/TurnListView.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/TurnDetailView.kt`
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/ConversationRootView.kt`

> Android 视图，纯绘制，无单测；编译验证。

- [ ] **Step 1: ConversationColors.kt**

```kotlin
package com.debugtools.conversation.view

import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnOutcome

object ConversationColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val ACCENT = 0xFF2DD4BF.toInt()
    val SUCCESS = 0xFF48BB78.toInt()
    val FAILED = 0xFFF43F5E.toInt()
    val WARN = 0xFFF6AD55.toInt()
    val NEUTRAL = 0xFF64748B.toInt()
    val EDGE = 0xFF4A5568.toInt()

    fun stageColor(s: StageStatus): Int = when (s) {
        StageStatus.SUCCESS -> SUCCESS
        StageStatus.FAILED -> FAILED
        StageStatus.RUNNING -> NEUTRAL
        StageStatus.SKIPPED -> TEXT_DIM
    }

    fun outcomeColor(o: TurnOutcome): Int = when (o) {
        TurnOutcome.SUCCESS -> SUCCESS
        TurnOutcome.FAILED -> FAILED
        TurnOutcome.TIMEOUT -> WARN
        TurnOutcome.ABORTED -> TEXT_DIM
    }
}
```

- [ ] **Step 2: SessionListView.kt（L1: 会话列表）**

```kotlin
package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.TurnOutcome

@SuppressLint("ViewConstructor")
class SessionListView(
    context: Context,
    sessions: List<ConversationSession>,
    onPick: (ConversationSession) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        if (sessions.isEmpty()) {
            addView(TextView(context).apply {
                text = "暂无对话记录。请调用 ConversationTracer 上报。"
                setTextColor(ConversationColors.TEXT_DIM); textSize = 12f
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            })
        }
        sessions.forEach { s ->
            val ok = s.turns.count { it.outcome == TurnOutcome.SUCCESS }
            val fail = s.turns.size - ok
            addView(TextView(context).apply {
                text = buildString {
                    append("对话 · ${s.turns.size}轮 · ✓$ok ✗$fail")
                    if (s.endedAtWallMs == null) append(" · (进行中)")
                }
                setTextColor(ConversationColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(ConversationColors.SURFACE) }
                setOnClickListener { onPick(s) }
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * density).toInt() })
        }
    }
}
```

- [ ] **Step 3: TurnListView.kt（L2: 轮次列表）**

```kotlin
package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.TurnOutcome

@SuppressLint("ViewConstructor")
class TurnListView(
    context: Context,
    turns: List<ConversationTurn>,
    onPick: (ConversationTurn) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    init {
        orientation = VERTICAL
        turns.forEach { t ->
            val dur = if (t.endUptimeMs != null) t.endUptimeMs - t.startUptimeMs else null
            val label = t.userInput?.take(20) ?: "(无文本)"
            addView(TextView(context).apply {
                text = "#${t.turnIndex}  $label  ·${if (dur != null) " ${dur}ms" else ""} ${outcomeEmoji(t.outcome)}"
                setTextColor(ConversationColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
                background = GradientDrawable().apply { cornerRadius = 8f * density; setColor(ConversationColors.SURFACE) }
                setOnClickListener { onPick(t) }
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (6 * density).toInt() })
        }
    }

    private fun outcomeEmoji(o: TurnOutcome) = when (o) {
        TurnOutcome.SUCCESS -> "✓"
        TurnOutcome.FAILED -> "✗"
        TurnOutcome.TIMEOUT -> "⏱"
        TurnOutcome.ABORTED -> "⊘"
    }
}
```

- [ ] **Step 4: TurnDetailView.kt（L3: 阶段时间线 + 展开 + 诊断）**

```kotlin
package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.debugtools.conversation.protocol.ConversationTurn

/**
 * Top section: stage timeline (simplified Gantt).
 * Paints one colored bar per stage, left-to-right by offset, plus the stage name label.
 */
@SuppressLint("ViewConstructor")
class TurnDetailView(context: Context, private val turn: ConversationTurn) : View(context) {

    private val density = resources.displayMetrics.density
    private val barH = 20f * density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ConversationColors.TEXT; textSize = 10f * density }
    private val maxOffset = turn.stages.mapNotNull { it.endOffsetMs }.maxOrNull() ?: 1L

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (barH + 8f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        turn.stages.forEach { s ->
            val sx = width * s.startOffsetMs / maxOffset.toFloat()
            val ex = (if (s.endOffsetMs != null) width * s.endOffsetMs / maxOffset.toFloat()
                       else width.toFloat()).coerceAtLeast(sx + 4f)
            val r = RectF(sx, 2f * density, ex, 2f * density + barH)
            bar.color = ConversationColors.stageColor(s.status)
            canvas.drawRoundRect(r, 3f * density, 3f * density, bar)
            canvas.drawText(s.name.take(5), sx + 2f * density, 2f * density + barH - 4f * density, label)
        }
    }
}
```

- [ ] **Step 5: ConversationRootView.kt（三层导航容器）**

```kotlin
package com.debugtools.conversation.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.conversation.analyzer.TurnAnalyzer
import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn

/**
 * Three-layer navigation: session list → turn list → turn detail (timeline + stage info + diagnostics).
 *
 * [loadSessions] is invoked on construction AND onAttachedToWindow so newly written
 * sessions appear when the tab is re-opened.
 */
@SuppressLint("ViewConstructor")
class ConversationRootView(
    context: Context,
    private val loadSessions: () -> List<ConversationSession>
) : ScrollView(context) {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(p(12), p(12), p(12), p(12)) }
    private var sessions: List<ConversationSession> = emptyList()

    private fun p(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(ConversationColors.BG)
        addView(content)
        sessions = loadSessions()
        showList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sessions = loadSessions()
        showList()
    }

    // ── L1: session list ──

    private fun showList() {
        content.removeAllViews()
        content.addView(header("对话链路 · 最近 ${sessions.size} 次"))
        content.addView(SessionListView(context, sessions) { showTurns(it) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ── L2: turn list ──

    private fun showTurns(session: ConversationSession) {
        content.removeAllViews()
        content.addView(backBtn { showList() })
        content.addView(header("${session.turns.size} 轮 · ${session.metadata?.get("scene")?.let { "$it" } ?: ""}"))
        content.addView(TurnListView(context, session.turns) { showDetail(it) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    // ── L3: turn detail ──

    private fun showDetail(turn: ConversationTurn) {
        content.removeAllViews()
        content.addView(backBtn { showTurns(sessions.first { it.turns.any { t -> t.turnId == turn.turnId } }) })
        val dur = if (turn.endUptimeMs != null) "${turn.endUptimeMs - turn.startUptimeMs}ms" else "进行中"
        content.addView(header("#${turn.turnIndex}  ${turn.userInput?.take(30) ?: "(无文本)"}  ·$dur"))
        content.addView(TurnDetailView(context, turn), lp())

        // stage info rows
        content.addView(header("阶段详情"))
        turn.stages.forEach { s ->
            val sb = StringBuilder("${s.name} [${statusLabel(s.status)}] ${s.startOffsetMs}-${s.endOffsetMs ?: "?"}ms")
            if (!s.input.isNullOrBlank()) sb.append("\n  ← ${s.input.take(60)}")
            if (!s.output.isNullOrBlank()) sb.append("\n  → ${s.output.take(60)}")
            if (!s.error.isNullOrBlank()) sb.append("\n  ⚠ ${s.error}")
            content.addView(dim(sb.toString()))
        }

        // diagnostics
        content.addView(header("⚠ 诊断"))
        val issues = TurnAnalyzer.analyze(turn)
        if (issues.isEmpty()) content.addView(dim("无异常"))
        else issues.forEach { content.addView(dim("• [${it.type}] ${it.stageName ?: ""} ${it.detail}")) }
    }

    private fun statusLabel(s: com.debugtools.conversation.protocol.StageStatus) = when (s) {
        com.debugtools.conversation.protocol.StageStatus.SUCCESS -> "✓"
        com.debugtools.conversation.protocol.StageStatus.FAILED -> "✗"
        com.debugtools.conversation.protocol.StageStatus.RUNNING -> "…"
        com.debugtools.conversation.protocol.StageStatus.SKIPPED -> "-"
    }

    // ── helpers ──

    private fun lp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = p(8) }

    private fun header(t: String) = TextView(context).apply {
        text = t; setTextColor(ConversationColors.TEXT); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, p(10), 0, p(4))
    }

    private fun dim(t: String) = TextView(context).apply {
        text = t; setTextColor(ConversationColors.TEXT_DIM); textSize = 11f; setPadding(0, p(2), 0, p(2))
    }

    private fun backBtn(onClick: () -> Unit) = TextView(context).apply {
        text = "← 返回"; setTextColor(ConversationColors.ACCENT); textSize = 12f; setPadding(0, p(4), 0, p(8))
        setOnClickListener { onClick() }
    }
}
```

- [ ] **Step 6: 编译确认**

Run: `./gradlew :debugtools-conversation:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/view/
git commit -m "feat(conversation): add three-layer views (session list, turn list, turn detail with stage timeline)"
```

---

## Task 8: ConversationMonitorModule（DebugModule 入口）+ 整模块构建

**Files:**
- Create: `debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationMonitorModule.kt`

- [ ] **Step 1: 实现 ConversationMonitorModule**

```kotlin
package com.debugtools.conversation

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.view.ConversationRootView

class ConversationMonitorModule : DebugModule {

    override val moduleId: String = "conversation"
    override val tabTitle: String = "对话链路"

    private var appContext: Context? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        appContext = context.applicationContext
    }

    override fun onDetach() { appContext = null }

    override fun createContentView(context: Context): View {
        return ConversationRootView(context) {
            mergeCurrent(ConversationTracer.currentSession(), ConversationTracer.loadSessions())
        }
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun getBriefItems(): List<BriefItem> {
        val current = ConversationTracer.currentSession() ?: return emptyList()
        val fail = current.turns.count { it.outcome.name == "FAILED" }
        return listOf(BriefItem(text = "对话 ${current.turns.size}轮" + if (fail > 0) " · ${fail}失败" else ""))
    }

    private fun mergeCurrent(current: ConversationSession?, persisted: List<ConversationSession>): List<ConversationSession> {
        if (current == null) return persisted
        val rest = persisted.filter { it.sessionId != current.sessionId }
        return listOf(current) + rest
    }
}
```

- [ ] **Step 2: 整模块构建 + 全量测试**

Run: `./gradlew :debugtools-conversation:assembleDebug :debugtools-conversation:test`
Expected: BUILD SUCCESSFUL；所有测试（协议 + recorder + store + analyzer）全过。

- [ ] **Step 3: 提交**

```bash
git add debugtools-conversation/src/main/kotlin/com/debugtools/conversation/ConversationMonitorModule.kt
git commit -m "feat(conversation): add ConversationMonitorModule entry point"
```

---

## Task 9: 在 sample app 接入并产生样例对话数据

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`

- [ ] **Step 1: app 依赖加入模块**

在 `app/build.gradle.kts` 的 dependencies 里，`implementation(project(":debugtools-startup"))` 之后加：
```kotlin
    implementation(project(":debugtools-conversation"))
```

- [ ] **Step 2: 在 MainActivity initDebugTools 中注册模块并初始化 + 写入样例数据**

在 `initDebugTools()` 的 builder 链里，`.register(StartupMonitorModule())` 之后加：
```kotlin
                .register(ConversationMonitorModule())
```

在 `btnGenStartup.isEnabled = true` 之后加：
```kotlin
            btnGenTraffic.isEnabled = true
```

在文件顶部 import 区添加：
```kotlin
import com.debugtools.conversation.ConversationTracer
import com.debugtools.conversation.ConversationMonitorModule
import com.debugtools.conversation.protocol.ConversationTurn
import com.debugtools.conversation.protocol.StageStatus
import com.debugtools.conversation.protocol.TurnOutcome
import com.debugtools.conversation.protocol.TurnStage
```

在 `initDebugTools()` 成功回调内，注册模块后添加初始化 + 样例数据：
```kotlin
            // 初始化对话链路追踪
            ConversationTracer.init(applicationContext)
```

添加一个按钮 + handler 直接写样例 turns：
```kotlin
        btnGenConversation = Button(this).apply {
            text = "💬 生成示例对话链路（3 轮）"
            isEnabled = false
            setOnClickListener { generateSampleConversation() }
        }
        root.addView(btnGenConversation)
```

在 enable 块添加：
```kotlin
            btnGenConversation.isEnabled = true
```

handler 方法：
```kotlin
    /** Write 3 varied sample conversation turns to the tracer. */
    private fun generateSampleConversation() {
        appendLog("→ 写入示例对话链路…")
        lifecycleScope.launch(Dispatchers.IO) {
            ConversationTracer.startSession("demo-session-1", mapOf("scene" to "导航"))

            // Turn 1: success
            ConversationTracer.submitTurn(ConversationTurn(
                turnId = "demo-t1", turnIndex = 1, sessionId = "demo-session-1",
                startUptimeMs = SystemClock.uptimeMillis(),
                endUptimeMs = SystemClock.uptimeMillis() + 800,
                userInput = "导航到最近的加油站",
                stages = listOf(
                    TurnStage("唤醒", 0, 200, StageStatus.SUCCESS, null, null, null, "main"),
                    TurnStage("ASR", 200, 500, StageStatus.SUCCESS, "[audio]", "导航到最近的加油站", null, "asr-1"),
                    TurnStage("NLU", 500, 550, StageStatus.SUCCESS, "导航到最近的加油站", """{"intent":"导航","slots":{"dest":"加油站"}}""", null, "nlu-1"),
                    TurnStage("TTS", 550, 750, StageStatus.SUCCESS, null, "已为您找到最近的加油站", null, "tts-1"),
                    TurnStage("执行", 750, 800, StageStatus.SUCCESS, null, "OK", null, "exec-1")
                ),
                outcome = TurnOutcome.SUCCESS,
                tags = listOf("导航")
            ))

            // Turn 2: failure (NLU fails)
            ConversationTracer.submitTurn(ConversationTurn(
                turnId = "demo-t2", turnIndex = 2, sessionId = "demo-session-1",
                startUptimeMs = SystemClock.uptimeMillis(),
                endUptimeMs = SystemClock.uptimeMillis() + 600,
                userInput = "打开空调",
                stages = listOf(
                    TurnStage("唤醒", 0, 150, StageStatus.SUCCESS, null, null, null, "main"),
                    TurnStage("ASR", 150, 400, StageStatus.SUCCESS, "[audio]", "打开空调", null, "asr-1"),
                    TurnStage("NLU", 400, 500, StageStatus.FAILED, "打开空调", null, "IntentNotFoundException: 未知意图", "nlu-1"),
                    TurnStage("TTS", 500, 600, StageStatus.SKIPPED, null, null, null, "tts-1")
                ),
                outcome = TurnOutcome.FAILED,
                tags = listOf("空调")
            ))

            // Turn 3: timeout + slow stage (TTS 900ms)
            ConversationTracer.submitTurn(ConversationTurn(
                turnId = "demo-t3", turnIndex = 3, sessionId = "demo-session-1",
                startUptimeMs = SystemClock.uptimeMillis(),
                endUptimeMs = SystemClock.uptimeMillis() + 2000,
                userInput = "播放周杰伦的歌",
                stages = listOf(
                    TurnStage("唤醒", 0, 100, StageStatus.SUCCESS, null, null, null, "main"),
                    TurnStage("ASR", 100, 400, StageStatus.SUCCESS, "[audio]", "播放周杰伦的歌", null, "asr-1"),
                    TurnStage("NLU", 400, 450, StageStatus.SUCCESS, "播放周杰伦的歌", """{"intent":"播放音乐","slots":{"artist":"周杰伦"}}""", null, "nlu-1"),
                    TurnStage("TTS", 450, 1350, StageStatus.SUCCESS, null, "好的，为您播放周杰伦", null, "tts-1"),  // 900ms = SLOW
                    TurnStage("执行", 1350, 2000, StageStatus.RUNNING, null, null, null, "exec-1")
                ),
                outcome = TurnOutcome.TIMEOUT,
                tags = listOf("音乐")
            ))

            ConversationTracer.endSession("demo-session-1")
            runOnUiThread { appendLog("✅ 已写入 3 轮示例对话 — 打开「对话链路」Tab 查看") }
        }
    }
```

- [ ] **Step 3: 构建**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/build.gradle.kts app/src/main/kotlin/com/debugtools/sample/MainActivity.kt
git commit -m "feat(app): demo conversation trace with 3 simulated turns"
```

---

## Task 10: README

**Files:**
- Create: `debugtools-conversation/README.md`

- [ ] **Step 1: 写 README**

```markdown
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
| 流水线间隙 | 前后阶段不连续,有空间隔 |

## 约束

- 仅同进程上报;数据存 `filesDir/conversation`,保留最近 50 次,**随 App 卸载删除**;不做网络上报。
- 阶段名不限枚举,接入方自定;Tool 只展示不校验。
- 整轮提交(`submitTurn`),不做流式逐阶段上报。
- 不做 confidence。
- 后续快照录制模块会聚合本模块数据。
```

- [ ] **Step 2: 提交**

```bash
git add debugtools-conversation/README.md
git commit -m "docs(conversation): add module README with integration guide"
```

---

## 验收清单

- [ ] `./gradlew :debugtools-conversation:test` 全绿（协议/recorder/store/analyzer）
- [ ] `:debugtools-conversation:assembleDebug` 与 `:app:assembleDebug` 通过
- [ ] 协议四件套（TurnStage/ConversationTurn/ConversationSession/TurnIssue）JSON 往返
- [ ] startSession / submitTurn / endSession + 兜底行为符合 spec
- [ ] 持久化最近 50 次，App 私有目录
- [ ] 「对话链路」Tab：会话列表 → 轮次列表 → 轮次详情（阶段时间线 + input/output/error + 诊断）
- [ ] demo 产生 3 轮样例对话（成功/失败/超时各一），可见
