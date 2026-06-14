# debugtools-logcat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `debugtools-logcat` SDK module that tails the calling app's logcat (own UID only), shows lines in a single-column list filtered by level, and supports manual record-to-temp-file + share-intent export.

**Architecture:** Background thread spawns `logcat -v threadtime` subprocess and reads stdout line-by-line; `LogcatParser` parses each line into a `LogLine`; `LogRepository` keeps the last 5000 lines in a thread-safe ring buffer and exposes a `StateFlow<Snapshot>` (Snapshot includes a `tick` field to dodge MutableStateFlow's structural-dedup, reusing the perfmon lesson). `LogRecorder` optionally tees writes into a temp file with a size cap. `LogcatPresenter` filters by selected levels, throttles via `sample(200ms)`, and pushes view models to `LogcatRootView` (toolbar + list + detail overlay).

**Tech Stack:** Kotlin 1.9.22, Android API 26+, kotlinx-coroutines 1.7.3, JUnit 4, Robolectric 4.11.1, kotlinx-coroutines-test.

---

## File Structure

```
debugtools-logcat/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   └── kotlin/com/debugtools/logcat/
│       ├── LogcatModule.kt            ← DebugModule entry + Builder
│       ├── Config.kt
│       ├── data/
│       │   ├── LogLevel.kt
│       │   ├── LogLine.kt
│       │   └── RecordingState.kt
│       ├── source/
│       │   ├── LogcatParser.kt        ← line → LogLine
│       │   └── LogcatProducer.kt      ← subprocess + reader thread
│       ├── recorder/
│       │   └── LogRecorder.kt         ← temp file + size cap + share
│       ├── repository/
│       │   └── LogRepository.kt       ← ringbuffer + StateFlow
│       ├── presenter/
│       │   ├── LogcatView.kt          ← view interface + ViewModel
│       │   └── LogcatPresenter.kt
│       └── view/
│           ├── LogcatRootView.kt
│           ├── LogToolbarView.kt
│           ├── LogListView.kt
│           └── LogDetailOverlay.kt
└── src/test/kotlin/com/debugtools/logcat/
    ├── ConfigTest.kt
    ├── source/
    │   └── LogcatParserTest.kt
    ├── recorder/
    │   └── LogRecorderTest.kt
    ├── repository/
    │   └── LogRepositoryTest.kt
    ├── presenter/
    │   └── LogcatPresenterTest.kt
    └── LogcatModuleTest.kt
```

**Notes for the implementer:**

- Lessons from perfmon worth keeping in mind:
  - **Kotlin expression-body + early `return`**: `fun foo() = try { ... if (...) return null ... } catch (...) { null }` does NOT compile. Use block-body `fun foo(): T? { return try { ... } catch (...) { null } }` when you need an early `return` inside.
  - **StateFlow dedup**: `MutableStateFlow` only emits when the new value `!= old`. Data classes containing references to mutable objects (like `ArrayDeque<LogLine>`) will silently dedupe when only the inner state changed. The fix is built into the spec via `Snapshot.tick: Long = 0L` auto-incrementing on every publish.
  - **Spec deviations for tests**: don't relax production behavior to make a test pass. If a test is too strict for an environment (e.g., Robolectric can't shadow some API), relax the assertion, not the contract.

---

## Task 1: Gradle Module Setup

**Files:**
- Create: `debugtools-logcat/build.gradle.kts`
- Create: `debugtools-logcat/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`

- [ ] **Step 1: Add module to `settings.gradle.kts`**

Open `/Users/xianxiaoli/ClaudeProjects/DebugTools/settings.gradle.kts` and add this line right after the existing `include(":debugtools-perfmon")`:

```kotlin
include(":debugtools-logcat")
```

- [ ] **Step 2: Create `debugtools-logcat/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debugtools.logcat"
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
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
```

- [ ] **Step 3: Create `debugtools-logcat/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: Verify build**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add settings.gradle.kts debugtools-logcat/ && git commit -m "chore(logcat): add empty module with gradle setup"
```

(Append `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>` trailer via HEREDOC per repo convention.)

---

## Task 2: Data Models

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/data/LogLevel.kt`
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/data/LogLine.kt`
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/data/RecordingState.kt`

- [ ] **Step 1: Create `LogLevel.kt`**

```kotlin
package com.debugtools.logcat.data

/** Standard logcat levels. F (Fatal) is merged into ERROR; S (Silent) is not a real level. */
enum class LogLevel(val code: Char) {
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E');

    companion object {
        fun fromCode(c: Char): LogLevel? = when (c) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARN
            'E', 'F' -> ERROR
            else -> null
        }
    }
}
```

- [ ] **Step 2: Create `LogLine.kt`**

```kotlin
package com.debugtools.logcat.data

data class LogLine(
    val timestamp: Long,    // millis (current year assumed; logcat doesn't emit year)
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String
)
```

- [ ] **Step 3: Create `RecordingState.kt`**

```kotlin
package com.debugtools.logcat.data

import java.io.File

data class RecordingState(
    val startedAt: Long,
    val countSoFar: Int,
    val bytesSoFar: Long,
    val tempFile: File
)
```

- [ ] **Step 4: Verify compiles**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:assembleDebug 2>&1 | tail -3`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/data/ && git commit -m "feat(logcat): add data models (LogLevel, LogLine, RecordingState)"
```

(Append Co-Authored-By trailer.)

---

## Task 3: Config

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/Config.kt`
- Create: `debugtools-logcat/src/test/kotlin/com/debugtools/logcat/ConfigTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// debugtools-logcat/src/test/kotlin/com/debugtools/logcat/ConfigTest.kt
package com.debugtools.logcat

import com.debugtools.logcat.data.LogLevel
import org.junit.Assert.*
import org.junit.Test

class ConfigTest {

    @Test fun `default values match spec`() {
        val c = Config()
        assertEquals(5000, c.bufferSize)
        assertEquals(20, c.maxRecordingMb)
        assertEquals(200L, c.throttleMs)
        assertEquals(LogLevel.values().toSet(), c.defaultLevels)
    }

    @Test fun `custom values are stored`() {
        val c = Config(
            bufferSize = 1000,
            maxRecordingMb = 5,
            throttleMs = 50L,
            defaultLevels = setOf(LogLevel.ERROR)
        )
        assertEquals(1000, c.bufferSize)
        assertEquals(5, c.maxRecordingMb)
        assertEquals(50L, c.throttleMs)
        assertEquals(setOf(LogLevel.ERROR), c.defaultLevels)
    }

    @Test fun `maxRecordingBytes derived from maxRecordingMb`() {
        val c = Config(maxRecordingMb = 3)
        assertEquals(3L * 1024 * 1024, c.maxRecordingBytes)
    }
}
```

- [ ] **Step 2: Run test to confirm compile failure**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: compile error — `Config` class not found.

- [ ] **Step 3: Create `Config.kt`**

```kotlin
package com.debugtools.logcat

import com.debugtools.logcat.data.LogLevel

/**
 * Tunable settings. Range checks live in [LogcatModule.Builder] which `coerceIn` each
 * field; the data class itself stores raw values.
 */
data class Config(
    val bufferSize: Int = 5000,
    val maxRecordingMb: Int = 20,
    val throttleMs: Long = 200L,
    val defaultLevels: Set<LogLevel> = LogLevel.values().toSet()
) {
    val maxRecordingBytes: Long get() = maxRecordingMb.toLong() * 1024 * 1024
}
```

- [ ] **Step 4: Run tests**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/Config.kt debugtools-logcat/src/test/kotlin/com/debugtools/logcat/ConfigTest.kt && git commit -m "feat(logcat): add Config with default thresholds"
```

---

## Task 4: LogcatParser

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/source/LogcatParser.kt`
- Create: `debugtools-logcat/src/test/kotlin/com/debugtools/logcat/source/LogcatParserTest.kt`

Parses lines from `logcat -v threadtime`. Format:
```
MM-DD HH:MM:SS.mmm  PID  TID L Tag: message
```

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.logcat.source

import com.debugtools.logcat.data.LogLevel
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class LogcatParserTest {

    private val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    private val parser = LogcatParser(currentYear)

    @Test fun `parses standard threadtime line`() {
        val line = "06-15 14:23:01.123  12345  12350 I MyTag: hello world"
        val r = parser.parse(line)
        assertNotNull(r)
        assertEquals(12345, r!!.pid)
        assertEquals(12350, r.tid)
        assertEquals(LogLevel.INFO, r.level)
        assertEquals("MyTag", r.tag)
        assertEquals("hello world", r.message)
    }

    @Test fun `tag with spaces is preserved`() {
        val line = "06-15 14:23:01.123  12345  12350 W My Tag With Spaces: oops"
        val r = parser.parse(line)
        assertNotNull(r)
        assertEquals("My Tag With Spaces", r!!.tag)
        assertEquals("oops", r.message)
    }

    @Test fun `message containing colons is preserved fully`() {
        val line = "06-15 14:23:01.123  12345  12350 D Net: GET http://x.com:8080/foo: 200"
        val r = parser.parse(line)
        assertNotNull(r)
        assertEquals("Net", r!!.tag)
        assertEquals("GET http://x.com:8080/foo: 200", r.message)
    }

    @Test fun `unrecognized line returns null`() {
        assertNull(parser.parse(""))
        assertNull(parser.parse("--------- beginning of main"))
        assertNull(parser.parse("    at com.example.Foo.bar(Foo.java:42)"))  // stack trace continuation
    }

    @Test fun `all level codes map correctly`() {
        listOf('V' to LogLevel.VERBOSE, 'D' to LogLevel.DEBUG, 'I' to LogLevel.INFO,
               'W' to LogLevel.WARN, 'E' to LogLevel.ERROR, 'F' to LogLevel.ERROR).forEach { (c, lvl) ->
            val r = parser.parse("06-15 14:23:01.123  1  1 $c Tag: msg")
            assertNotNull("level $c", r)
            assertEquals("level $c", lvl, r!!.level)
        }
    }

    @Test fun `timestamp built from current year`() {
        val r = parser.parse("06-15 14:23:01.123  1  1 I Tag: msg")
        val cal = Calendar.getInstance().apply { timeInMillis = r!!.timestamp }
        assertEquals(currentYear, cal.get(Calendar.YEAR))
        assertEquals(Calendar.JUNE, cal.get(Calendar.MONTH))
        assertEquals(15, cal.get(Calendar.DAY_OF_MONTH))
        assertEquals(14, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(23, cal.get(Calendar.MINUTE))
        assertEquals(1, cal.get(Calendar.SECOND))
        assertEquals(123, cal.get(Calendar.MILLISECOND))
    }
}
```

- [ ] **Step 2: Run test to confirm compile failure**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: compile error — `LogcatParser` not found.

- [ ] **Step 3: Create `LogcatParser.kt`**

```kotlin
package com.debugtools.logcat.source

import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.LogLine
import java.util.Calendar
import java.util.TimeZone

/**
 * Parses lines from `logcat -v threadtime`:
 *   MM-DD HH:MM:SS.mmm  PID  TID L Tag: message
 *
 * Tag may contain spaces. Message may contain colons. The first ": " after the
 * level token separates tag from message.
 *
 * Returns null for unrecognized lines (banners, stack-trace continuations, empty lines).
 */
class LogcatParser(private val year: Int = Calendar.getInstance().get(Calendar.YEAR)) {

    private val regex = Regex(
        """^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?):\s?(.*)$"""
    )

    fun parse(raw: String): LogLine? {
        val m = regex.matchEntire(raw) ?: return null
        val (mm, dd, hh, mi, ss, ms, pid, tid, lvlCh, tag, msg) = m.destructured
        val level = LogLevel.fromCode(lvlCh[0]) ?: return null
        val ts = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, mm.toInt() - 1)
            set(Calendar.DAY_OF_MONTH, dd.toInt())
            set(Calendar.HOUR_OF_DAY, hh.toInt())
            set(Calendar.MINUTE, mi.toInt())
            set(Calendar.SECOND, ss.toInt())
            set(Calendar.MILLISECOND, ms.toInt())
        }.timeInMillis
        return LogLine(
            timestamp = ts,
            pid = pid.toInt(),
            tid = tid.toInt(),
            level = level,
            tag = tag,
            message = msg
        )
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -15`
Expected: 6 LogcatParserTest tests PASS + 3 ConfigTest still PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/source/LogcatParser.kt debugtools-logcat/src/test/kotlin/com/debugtools/logcat/source/LogcatParserTest.kt && git commit -m "feat(logcat): add LogcatParser for -v threadtime lines"
```

---

## Task 5: LogRecorder

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/recorder/LogRecorder.kt`
- Create: `debugtools-logcat/src/test/kotlin/com/debugtools/logcat/recorder/LogRecorderTest.kt`

Writes `LogLine`s to a file, tracks size, signals "should stop" when the size cap is exceeded. Pure JVM — no Android dependencies (Android-side share intent lives in `LogcatModule`).

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.logcat.recorder

import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.LogLine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogRecorderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun line(msg: String = "hello") = LogLine(
        timestamp = 1_750_000_000_000L,
        pid = 100, tid = 200,
        level = LogLevel.INFO,
        tag = "T", message = msg
    )

    @Test fun `write appends formatted line to file`() {
        val file = File(tmp.root, "rec.log")
        val r = LogRecorder(file, maxBytes = 1_000_000)
        r.write(line("first"))
        r.write(line("second"))
        r.close()
        val content = file.readText()
        assertTrue("must contain first", content.contains("first"))
        assertTrue("must contain second", content.contains("second"))
        assertTrue("must have level I", content.contains(" I "))
        assertTrue("must have tag T", content.contains(" T: "))
    }

    @Test fun `bytesWritten tracks file size`() {
        val file = File(tmp.root, "rec.log")
        val r = LogRecorder(file, maxBytes = 1_000_000)
        assertEquals(0L, r.bytesWritten)
        r.write(line())
        assertTrue("bytes > 0", r.bytesWritten > 0L)
        r.close()
    }

    @Test fun `shouldStop becomes true after cap exceeded`() {
        val file = File(tmp.root, "rec.log")
        // Cap = 50 bytes (tiny). One line is ~60 bytes already.
        val r = LogRecorder(file, maxBytes = 50)
        assertFalse(r.shouldStop)
        r.write(line("x".repeat(80)))
        assertTrue(r.shouldStop)
        r.close()
    }

    @Test fun `close is idempotent`() {
        val file = File(tmp.root, "rec.log")
        val r = LogRecorder(file, maxBytes = 1000)
        r.write(line())
        r.close()
        r.close()  // must not throw
    }
}
```

- [ ] **Step 2: Run test to confirm compile failure**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: compile error — `LogRecorder` not found.

- [ ] **Step 3: Create `LogRecorder.kt`**

```kotlin
package com.debugtools.logcat.recorder

import com.debugtools.logcat.data.LogLine
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tees [LogLine]s into a flat text file. Format mirrors logcat -v threadtime so
 * the dumped file can be re-read by any logcat viewer. Tracks bytes written and
 * signals [shouldStop] when [maxBytes] is exceeded — caller is responsible for
 * checking and stopping.
 *
 * Not thread-safe — callers serialize writes (LogRepository does this via @Synchronized).
 */
class LogRecorder(val file: File, private val maxBytes: Long) {

    private val writer: BufferedWriter = BufferedWriter(FileWriter(file))
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    var bytesWritten: Long = 0L
        private set
    val shouldStop: Boolean get() = bytesWritten >= maxBytes
    private var closed = false

    fun write(line: LogLine) {
        if (closed) return
        val rendered = render(line)
        writer.write(rendered)
        writer.newLine()
        bytesWritten += rendered.length + 1
    }

    fun close() {
        if (closed) return
        try {
            writer.flush()
            writer.close()
        } catch (_: Exception) {
        }
        closed = true
    }

    private fun render(l: LogLine): String {
        val ts = timeFmt.format(Date(l.timestamp))
        return "$ts ${"%5d".format(l.pid)} ${"%5d".format(l.tid)} ${l.level.code} ${l.tag}: ${l.message}"
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: 4 LogRecorderTest tests PASS + earlier suites still green.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/recorder/LogRecorder.kt debugtools-logcat/src/test/kotlin/com/debugtools/logcat/recorder/LogRecorderTest.kt && git commit -m "feat(logcat): add LogRecorder for size-capped file recording"
```

---

## Task 6: LogRepository

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/repository/LogRepository.kt`
- Create: `debugtools-logcat/src/test/kotlin/com/debugtools/logcat/repository/LogRepositoryTest.kt`

Holds a thread-safe ring buffer + optional recorder + producer-alive flag. Exposes a single `StateFlow<Snapshot>`. `Snapshot.tick` is incremented on every publish so `MutableStateFlow` re-emits even when the inner list reference is the same (perfmon lesson).

- [ ] **Step 1: Write failing test**

```kotlin
package com.debugtools.logcat.repository

import com.debugtools.logcat.Config
import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.LogLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LogRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun line(msg: String, ts: Long = 1L, level: LogLevel = LogLevel.INFO) =
        LogLine(ts, 100, 100, level, "T", msg)

    @Test fun `append fills ringbuffer up to bufferSize`() {
        val repo = LogRepository(Config(bufferSize = 3))
        repo.append(line("a"))
        repo.append(line("b"))
        repo.append(line("c"))
        repo.append(line("d"))  // evicts a
        assertEquals(listOf("b", "c", "d"), repo.state.value.lines.map { it.message })
    }

    @Test fun `tick increments per publish`() {
        val repo = LogRepository(Config(bufferSize = 10))
        val initial = repo.state.value.tick
        repo.append(line("a"))
        repo.append(line("b"))
        assertEquals(initial + 2, repo.state.value.tick)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `state re-emits on every append`() = runTest(UnconfinedTestDispatcher()) {
        val repo = LogRepository(Config(bufferSize = 10))
        val emissions = mutableListOf<LogRepository.Snapshot>()
        val job = launch { repo.state.toList(emissions) }
        repo.append(line("a"))
        repo.append(line("b"))
        repo.append(line("c"))
        // initial + 3 appends = 4
        assertEquals(4, emissions.size)
        job.cancel()
    }

    @Test fun `startRecording creates file and writes subsequent lines`() {
        val repo = LogRepository(Config(bufferSize = 10, maxRecordingMb = 1))
        val file = File(tmp.root, "out.log")
        repo.startRecording(file)
        repo.append(line("recorded"))
        repo.append(line("alsoRecorded"))
        repo.stopRecording()
        val content = file.readText()
        assertTrue(content.contains("recorded"))
        assertTrue(content.contains("alsoRecorded"))
    }

    @Test fun `recording auto-stops when size cap exceeded`() {
        val repo = LogRepository(Config(bufferSize = 1000, maxRecordingMb = 1))
        // maxRecordingBytes = 1 MB; one of these is ~50 bytes, so 25k lines ≈ 1.25 MB
        val file = File(tmp.root, "out.log")
        repo.startRecording(file)
        repeat(30_000) { repo.append(line("x".repeat(40))) }
        assertNull("recording should have auto-stopped", repo.state.value.recording)
    }

    @Test fun `clear empties buffer`() {
        val repo = LogRepository(Config(bufferSize = 10))
        repo.append(line("a"))
        repo.append(line("b"))
        repo.clear()
        assertTrue(repo.state.value.lines.isEmpty())
    }

    @Test fun `markProducerDied updates flag`() {
        val repo = LogRepository(Config())
        assertTrue(repo.state.value.producerAlive)
        repo.markProducerDied()
        assertFalse(repo.state.value.producerAlive)
    }
}
```

- [ ] **Step 2: Run test to confirm compile failure**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: compile error — `LogRepository` not found.

- [ ] **Step 3: Create `LogRepository.kt`**

```kotlin
package com.debugtools.logcat.repository

import com.debugtools.logcat.Config
import com.debugtools.logcat.data.LogLine
import com.debugtools.logcat.data.RecordingState
import com.debugtools.logcat.recorder.LogRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * In-memory ring buffer + optional recorder + producer-alive flag, all behind a
 * single [StateFlow<Snapshot>]. Every mutation publishes a new Snapshot with an
 * incremented [tick] to dodge MutableStateFlow's structural-equality dedup
 * (Snapshot.lines is a fresh List but contains the same LogLine references, so
 * value equality fires; the tick bump breaks that).
 *
 * Thread-safe via @Synchronized on all mutating methods.
 */
class LogRepository(private val config: Config) {

    data class Snapshot(
        val lines: List<LogLine>,
        val recording: RecordingState?,
        val producerAlive: Boolean,
        val tick: Long = 0L
    )

    private val buffer = ArrayDeque<LogLine>(config.bufferSize)
    private var recorder: LogRecorder? = null
    private var startedAt: Long = 0L
    private val _state = MutableStateFlow(
        Snapshot(lines = emptyList(), recording = null, producerAlive = true)
    )
    val state: StateFlow<Snapshot> = _state
    private var tickCounter: Long = 0L

    @Synchronized
    fun append(line: LogLine) {
        if (buffer.size >= config.bufferSize) buffer.removeFirst()
        buffer.addLast(line)
        recorder?.let { r ->
            r.write(line)
            if (r.shouldStop) {
                r.close()
                recorder = null
            }
        }
        publish()
    }

    @Synchronized
    fun startRecording(file: File) {
        recorder?.close()
        recorder = LogRecorder(file, maxBytes = config.maxRecordingBytes)
        startedAt = System.currentTimeMillis()
        publish()
    }

    @Synchronized
    fun stopRecording(): File? {
        val r = recorder ?: return null
        r.close()
        recorder = null
        publish()
        return r.file
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        publish()
    }

    @Synchronized
    fun markProducerDied() {
        _state.value = _state.value.copy(producerAlive = false, tick = ++tickCounter)
    }

    @Synchronized
    fun markProducerAlive() {
        _state.value = _state.value.copy(producerAlive = true, tick = ++tickCounter)
    }

    private fun publish() {
        val rec = recorder?.let {
            RecordingState(
                startedAt = startedAt,
                countSoFar = countSinceRecordingStart(),
                bytesSoFar = it.bytesWritten,
                tempFile = it.file
            )
        }
        _state.value = Snapshot(
            lines = buffer.toList(),
            recording = rec,
            producerAlive = _state.value.producerAlive,
            tick = ++tickCounter
        )
    }

    // Simple approximation: we count from start by tracking baseline buffer size at startRecording.
    // For v1 we use a simpler proxy — incremented per write while recorder is active.
    private var recordingCount: Int = 0

    private fun countSinceRecordingStart(): Int = recordingCount

    init {
        // wire recorder.write counter via a lambda hook on append? Simpler: bump in append when recorder != null.
    }
}
```

Refine to actually bump `recordingCount` (the comment scaffolding above is provisional). Replace the `append` body and `startRecording` body to maintain the counter:

```kotlin
    @Synchronized
    fun append(line: LogLine) {
        if (buffer.size >= config.bufferSize) buffer.removeFirst()
        buffer.addLast(line)
        recorder?.let { r ->
            r.write(line)
            recordingCount++
            if (r.shouldStop) {
                r.close()
                recorder = null
            }
        }
        publish()
    }

    @Synchronized
    fun startRecording(file: File) {
        recorder?.close()
        recorder = LogRecorder(file, maxBytes = config.maxRecordingBytes)
        startedAt = System.currentTimeMillis()
        recordingCount = 0
        publish()
    }

    @Synchronized
    fun stopRecording(): File? {
        val r = recorder ?: return null
        r.close()
        recorder = null
        recordingCount = 0
        publish()
        return r.file
    }
```

(Final file should have one clean `append`, one `startRecording`, one `stopRecording` — the scaffolding above showed two passes for clarity; collapse them.)

- [ ] **Step 4: Run tests**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -15`
Expected: 7 LogRepositoryTest tests PASS + all earlier suites green.

- [ ] **Step 5: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/repository/LogRepository.kt debugtools-logcat/src/test/kotlin/com/debugtools/logcat/repository/LogRepositoryTest.kt && git commit -m "feat(logcat): add LogRepository with ringbuffer, recorder tee, and tick-bumped StateFlow"
```

---

## Task 7: LogcatProducer

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/source/LogcatProducer.kt`

No automated tests (spec §12 — depends on real logcat subprocess; verified manually in demo). Spawns `logcat -v threadtime`, reads stdout on a dedicated thread, parses each line, feeds the repository. Restarts up to 3 times on EOF/IOException before marking producer dead.

- [ ] **Step 1: Create `LogcatProducer.kt`**

```kotlin
package com.debugtools.logcat.source

import com.debugtools.logcat.repository.LogRepository
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Spawns `logcat -v threadtime` and feeds parsed lines to [repository]. Runs on a
 * dedicated [Thread] (not coroutine) because BufferedReader.readLine is blocking
 * and not cooperatively cancellable; Process.destroy() is the only way to unblock
 * it cleanly.
 *
 * Restarts the subprocess up to [maxRestarts] times on EOF (sub process killed by
 * system) or IOException. Final failure flips repository.markProducerDied().
 */
class LogcatProducer(
    private val repository: LogRepository,
    private val parser: LogcatParser,
    private val maxRestarts: Int = 3,
    private val restartDelayMs: Long = 1000L,
    private val processFactory: () -> Process = {
        ProcessBuilder("logcat", "-v", "threadtime").redirectErrorStream(true).start()
    }
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var process: Process? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "debugtools-logcat-producer").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        process?.destroy()
        thread?.join(500L)
        thread = null
        process = null
    }

    private fun runLoop() {
        var attempts = 0
        while (running && attempts <= maxRestarts) {
            val ok = readOnce()
            if (!running) return
            if (ok) {
                // EOF after a clean read; try restart
                attempts++
            } else {
                attempts++
            }
            if (attempts <= maxRestarts) {
                try { Thread.sleep(restartDelayMs) } catch (_: InterruptedException) { return }
            }
        }
        repository.markProducerDied()
    }

    /** Returns true if the loop exited because of EOF (clean), false on exception. */
    private fun readOnce(): Boolean {
        return try {
            val p = processFactory()
            process = p
            repository.markProducerAlive()
            BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: return true  // EOF
                    parser.parse(line)?.let { repository.append(it) }
                }
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            try { process?.destroy() } catch (_: Exception) {}
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/source/LogcatProducer.kt && git commit -m "feat(logcat): add LogcatProducer subprocess + reader thread with restart-on-EOF"
```

---

## Task 8: LogcatPresenter + LogcatView

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/presenter/LogcatView.kt`
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/presenter/LogcatPresenter.kt`
- Create: `debugtools-logcat/src/test/kotlin/com/debugtools/logcat/presenter/LogcatPresenterTest.kt`

Combines repository state with a `selectedLevels` filter, throttles via `sample(throttleMs)`, pushes filtered lines + toolbar state to view. Detach cancels.

- [ ] **Step 1: Create `LogcatView.kt`**

```kotlin
package com.debugtools.logcat.presenter

import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.LogLine
import com.debugtools.logcat.data.RecordingState

interface LogcatView {
    fun render(model: LogcatViewModel)
}

data class LogcatViewModel(
    val visibleLines: List<LogLine>,
    val selectedLevels: Set<LogLevel>,
    val recording: RecordingState?,
    val producerAlive: Boolean
)
```

- [ ] **Step 2: Write failing test for presenter**

```kotlin
package com.debugtools.logcat.presenter

import com.debugtools.logcat.Config
import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.LogLine
import com.debugtools.logcat.repository.LogRepository
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
class LogcatPresenterTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeView : LogcatView {
        var lastModel: LogcatViewModel? = null
        var renderCount = 0
        override fun render(model: LogcatViewModel) { lastModel = model; renderCount++ }
    }

    private fun line(msg: String, level: LogLevel = LogLevel.INFO) =
        LogLine(1L, 100, 100, level, "T", msg)

    @Test fun `view receives filtered lines per selectedLevels`() = runTest(dispatcher) {
        val repo = LogRepository(Config(bufferSize = 100, throttleMs = 0L))
        val view = FakeView()
        val presenter = LogcatPresenter(repo, Config(throttleMs = 0L), this)
        presenter.attachView(view)
        presenter.setSelectedLevels(setOf(LogLevel.ERROR))
        repo.append(line("info", LogLevel.INFO))
        repo.append(line("error", LogLevel.ERROR))
        advanceTimeBy(20L)
        val visible = view.lastModel!!.visibleLines
        assertEquals(1, visible.size)
        assertEquals("error", visible[0].message)
        presenter.detach()
    }

    @Test fun `default selectedLevels comes from Config`() = runTest(dispatcher) {
        val repo = LogRepository(Config(bufferSize = 100, throttleMs = 0L))
        val cfg = Config(throttleMs = 0L, defaultLevels = setOf(LogLevel.WARN))
        val view = FakeView()
        val presenter = LogcatPresenter(repo, cfg, this)
        presenter.attachView(view)
        repo.append(line("info", LogLevel.INFO))
        repo.append(line("warn", LogLevel.WARN))
        advanceTimeBy(20L)
        assertEquals(setOf(LogLevel.WARN), view.lastModel!!.selectedLevels)
        assertEquals(listOf("warn"), view.lastModel!!.visibleLines.map { it.message })
        presenter.detach()
    }

    @Test fun `detach stops further renders`() = runTest(dispatcher) {
        val repo = LogRepository(Config(bufferSize = 100, throttleMs = 0L))
        val view = FakeView()
        val presenter = LogcatPresenter(repo, Config(throttleMs = 0L), this)
        presenter.attachView(view)
        repo.append(line("a"))
        advanceTimeBy(20L)
        val before = view.renderCount
        presenter.detach()
        repo.append(line("b"))
        advanceTimeBy(20L)
        assertEquals(before, view.renderCount)
    }
}
```

- [ ] **Step 3: Run test to confirm compile failure**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: compile error — `LogcatPresenter` not found.

- [ ] **Step 4: Create `LogcatPresenter.kt`**

```kotlin
package com.debugtools.logcat.presenter

import com.debugtools.logcat.Config
import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class LogcatPresenter(
    private val repository: LogRepository,
    private val config: Config,
    private val scope: CoroutineScope
) {
    private var view: LogcatView? = null
    private var job: Job? = null
    private val selectedLevels = MutableStateFlow(config.defaultLevels)

    fun attachView(view: LogcatView) {
        this.view = view
        job = scope.launch {
            val source = combine(repository.state, selectedLevels) { state, levels ->
                LogcatViewModel(
                    visibleLines = state.lines.filter { it.level in levels },
                    selectedLevels = levels,
                    recording = state.recording,
                    producerAlive = state.producerAlive
                )
            }
            (if (config.throttleMs > 0L) source.sample(config.throttleMs) else source)
                .collect { this@LogcatPresenter.view?.render(it) }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        view = null
    }

    fun setSelectedLevels(levels: Set<LogLevel>) {
        selectedLevels.value = levels
    }
}
```

- [ ] **Step 5: Run tests**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -15`
Expected: 3 LogcatPresenterTest tests PASS + all earlier suites green.

- [ ] **Step 6: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/presenter/ debugtools-logcat/src/test/kotlin/com/debugtools/logcat/presenter/ && git commit -m "feat(logcat): add LogcatView interface and LogcatPresenter with level filter + throttle"
```

---

## Task 9: LogDetailOverlay

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogDetailOverlay.kt`

Full-screen overlay showing one `LogLine`'s details with a Copy button. No tests (pure rendering).

- [ ] **Step 1: Create `LogDetailOverlay.kt`**

```kotlin
package com.debugtools.logcat.view

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.logcat.data.LogLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen modal showing full details of one LogLine. Tap outside the card or
 * "关闭" to dismiss. "复制" copies the rendered text to the system clipboard.
 */
@SuppressLint("ViewConstructor")
class LogDetailOverlay(context: Context, private val line: LogLine, private val onDismiss: () -> Unit) :
    FrameLayout(context) {

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    init {
        setBackgroundColor(Color.parseColor("#A0000000"))
        setOnClickListener { onDismiss() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A202C"))
            setPadding(32, 32, 32, 32)
            setOnClickListener { /* swallow */ }
        }
        card.addView(field("时间", timeFmt.format(Date(line.timestamp))))
        card.addView(field("级别", line.level.name))
        card.addView(field("PID / TID", "${line.pid} / ${line.tid}"))
        card.addView(field("Tag", line.tag))
        card.addView(textBlock("Message", line.message))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }
        buttons.addView(Button(context).apply {
            text = "复制"
            setOnClickListener { copyToClipboard() }
        })
        buttons.addView(Button(context).apply {
            text = "关闭"
            setOnClickListener { onDismiss() }
        })
        card.addView(buttons)

        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = 64; leftMargin = 64; rightMargin = 64
        })
    }

    private fun field(label: String, value: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 8, 0, 8)
        addView(TextView(context).apply {
            text = "$label: "
            setTextColor(Color.parseColor("#A0AEC0"))
            textSize = 13f
        })
        addView(TextView(context).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = 13f
        })
    }

    private fun textBlock(label: String, value: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 8, 0, 8)
        addView(TextView(context).apply {
            text = "$label:"
            setTextColor(Color.parseColor("#A0AEC0"))
            textSize = 13f
            setPadding(0, 0, 0, 8)
        })
        addView(ScrollView(context).apply {
            addView(TextView(context).apply {
                text = value
                setTextColor(Color.WHITE)
                textSize = 13f
                setTextIsSelectable(true)
            })
        }, LayoutParams(LayoutParams.MATCH_PARENT, 600))
    }

    private fun copyToClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val rendered = "[${line.level.name}] ${line.tag} (${line.pid}/${line.tid}) @ ${timeFmt.format(Date(line.timestamp))}\n${line.message}"
        cm.setPrimaryClip(ClipData.newPlainText("logcat line", rendered))
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogDetailOverlay.kt && git commit -m "feat(logcat): add LogDetailOverlay for full-line view + copy"
```

---

## Task 10: LogToolbarView

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogToolbarView.kt`

Level chips + record / stop button + clear button. No tests (pure rendering).

- [ ] **Step 1: Create `LogToolbarView.kt`**

```kotlin
package com.debugtools.logcat.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.RecordingState

/**
 * Horizontal toolbar: 5 level chips + record/stop button + clear button.
 * Caller wires three callbacks: onLevelsChanged, onRecordToggle, onClear.
 */
@SuppressLint("ViewConstructor")
class LogToolbarView(context: Context) : LinearLayout(context) {

    var onLevelsChanged: ((Set<LogLevel>) -> Unit)? = null
    var onRecordToggle: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null

    private val chipsByLevel: Map<LogLevel, TextView>
    private val recordButton: Button
    private val selected = mutableSetOf<LogLevel>()

    init {
        orientation = HORIZONTAL
        setPadding(16, 12, 16, 12)
        setBackgroundColor(Color.parseColor("#1A202C"))

        chipsByLevel = LogLevel.values().associateWith { lvl ->
            TextView(context).apply {
                text = lvl.code.toString()
                textSize = 14f
                setPadding(24, 8, 24, 8)
                background = chipBackground(lvl, selected = false)
                setTextColor(Color.WHITE)
                val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                params.marginEnd = 8
                layoutParams = params
                setOnClickListener {
                    if (selected.contains(lvl)) selected.remove(lvl) else selected.add(lvl)
                    background = chipBackground(lvl, selected = selected.contains(lvl))
                    onLevelsChanged?.invoke(selected.toSet())
                }
            }
        }
        chipsByLevel.values.forEach { addView(it) }

        recordButton = Button(context).apply {
            text = "▶ 开始录制"
            setOnClickListener { onRecordToggle?.invoke() }
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            params.marginStart = 24
            layoutParams = params
        }
        addView(recordButton)

        addView(Button(context).apply {
            text = "🗑 清空"
            setOnClickListener { onClear?.invoke() }
            val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            params.marginStart = 8
            layoutParams = params
        })
    }

    /** Sync chip state from the presenter's model. */
    fun setSelectedLevels(levels: Set<LogLevel>) {
        selected.clear()
        selected.addAll(levels)
        chipsByLevel.forEach { (lvl, tv) ->
            tv.background = chipBackground(lvl, selected = lvl in selected)
        }
    }

    fun setRecording(state: RecordingState?) {
        recordButton.text = if (state == null) "▶ 开始录制"
        else {
            val secs = (System.currentTimeMillis() - state.startedAt) / 1000
            "■ 停止录制 (${secs}s, ${state.countSoFar} 条)"
        }
    }

    private fun chipBackground(level: LogLevel, selected: Boolean): GradientDrawable {
        val color = when (level) {
            LogLevel.VERBOSE -> "#718096"
            LogLevel.DEBUG   -> "#63B3ED"
            LogLevel.INFO    -> "#68D391"
            LogLevel.WARN    -> "#FBD38D"
            LogLevel.ERROR   -> "#FC8181"
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(if (selected) Color.parseColor(color) else Color.parseColor("#2D3748"))
            setStroke(2, Color.parseColor(color))
        }
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogToolbarView.kt && git commit -m "feat(logcat): add LogToolbarView (level chips + record + clear)"
```

---

## Task 11: LogListView

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogListView.kt`

ScrollView + vertical LinearLayout. Auto-scroll on by default, pauses when user scrolls up, resumes when scrolled back to bottom. Click row → opens LogDetailOverlay.

- [ ] **Step 1: Create `LogListView.kt`**

```kotlin
package com.debugtools.logcat.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.LogLine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vertical scrolling list of LogLines. Each row is a single-line TextView with
 * level-based color. Auto-scrolls to bottom on submit() unless the user has scrolled
 * up; a "↓ Resume" button surfaces when auto-scroll is paused.
 *
 * Click a row → onLineClick callback (typically opens [LogDetailOverlay]).
 */
@SuppressLint("ViewConstructor")
class LogListView(context: Context) : FrameLayout(context) {

    var onLineClick: ((LogLine) -> Unit)? = null

    private val scroll: ScrollView
    private val container: LinearLayout
    private val resumeButton: Button
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var autoScrollEnabled = true

    init {
        setBackgroundColor(Color.parseColor("#0F1419"))
        container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(container, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        resumeButton = Button(context).apply {
            text = "↓ Resume"
            visibility = GONE
            setOnClickListener {
                autoScrollEnabled = true
                visibility = GONE
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        addView(resumeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            rightMargin = 32; bottomMargin = 32
        })

        scroll.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) {
                if (autoScrollEnabled && !isScrolledToBottom()) {
                    autoScrollEnabled = false
                    resumeButton.visibility = VISIBLE
                }
            }
            false
        }
        scroll.viewTreeObserver.addOnScrollChangedListener {
            if (!autoScrollEnabled && isScrolledToBottom()) {
                autoScrollEnabled = true
                resumeButton.visibility = GONE
            }
        }
    }

    fun submit(lines: List<LogLine>) {
        container.removeAllViews()
        for (line in lines) {
            container.addView(buildRow(line))
        }
        if (autoScrollEnabled) {
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun buildRow(line: LogLine): TextView {
        return TextView(context).apply {
            text = "${timeFmt.format(Date(line.timestamp))} ${line.level.code} ${line.tag}: ${line.message}"
            setTextColor(levelColor(line.level))
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(16, 4, 16, 4)
            setOnClickListener { onLineClick?.invoke(line) }
        }
    }

    private fun isScrolledToBottom(): Boolean {
        val child = scroll.getChildAt(0) ?: return true
        val diff = child.bottom - (scroll.height + scroll.scrollY)
        return diff <= 40
    }

    private fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.VERBOSE -> Color.parseColor("#A0AEC0")
        LogLevel.DEBUG   -> Color.parseColor("#63B3ED")
        LogLevel.INFO    -> Color.parseColor("#68D391")
        LogLevel.WARN    -> Color.parseColor("#FBD38D")
        LogLevel.ERROR   -> Color.parseColor("#FC8181")
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogListView.kt && git commit -m "feat(logcat): add LogListView with auto-scroll + freeze-on-touch"
```

---

## Task 12: LogcatRootView + LogcatModule wiring

**Files:**
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogcatRootView.kt`
- Create: `debugtools-logcat/src/main/kotlin/com/debugtools/logcat/LogcatModule.kt`
- Create: `debugtools-logcat/src/test/kotlin/com/debugtools/logcat/LogcatModuleTest.kt`

`LogcatRootView` composes toolbar + list + optional banner + overlay. `LogcatModule` is the public DebugModule with Builder.

- [ ] **Step 1: Create `LogcatRootView.kt`**

```kotlin
package com.debugtools.logcat.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.data.LogLine
import com.debugtools.logcat.data.RecordingState
import com.debugtools.logcat.presenter.LogcatView
import com.debugtools.logcat.presenter.LogcatViewModel

@SuppressLint("ViewConstructor")
class LogcatRootView(
    context: Context,
    onLevelsChanged: (Set<LogLevel>) -> Unit,
    onRecordToggle: () -> Unit,
    onClear: () -> Unit,
    onRetryProducer: () -> Unit
) : FrameLayout(context), LogcatView {

    private val toolbar = LogToolbarView(context).also {
        it.onLevelsChanged = onLevelsChanged
        it.onRecordToggle = onRecordToggle
        it.onClear = onClear
    }
    private val list = LogListView(context)
    private val banner: TextView
    private val column: LinearLayout
    private var detailOverlay: LogDetailOverlay? = null

    init {
        setBackgroundColor(Color.parseColor("#0F1419"))
        column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        banner = TextView(context).apply {
            text = "⚠ logcat 子进程异常，已停止采集（点击重试）"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C53030"))
            setPadding(24, 16, 24, 16)
            textSize = 13f
            visibility = GONE
            setOnClickListener { onRetryProducer() }
        }
        column.addView(banner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        list.onLineClick = { line -> showDetail(line) }
    }

    override fun render(model: LogcatViewModel) {
        toolbar.setSelectedLevels(model.selectedLevels)
        toolbar.setRecording(model.recording)
        banner.visibility = if (model.producerAlive) GONE else VISIBLE
        list.submit(model.visibleLines)
    }

    private fun showDetail(line: LogLine) {
        detailOverlay?.let { removeView(it) }
        val overlay = LogDetailOverlay(context, line) { dismissDetail() }
        detailOverlay = overlay
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun dismissDetail() {
        detailOverlay?.let { removeView(it) }
        detailOverlay = null
    }
}
```

- [ ] **Step 2: Write failing test for `LogcatModule`**

```kotlin
package com.debugtools.logcat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.debugtools.logcat.data.LogLevel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogcatModuleTest {

    private lateinit var context: Context

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext() }

    @Test fun `moduleId and tabTitle are set`() {
        val m = LogcatModule.builder().build()
        assertEquals("debugtools_logcat", m.moduleId)
        assertEquals("日志", m.tabTitle)
    }

    @Test fun `builder clamps bufferSize to range`() {
        val tooSmall = LogcatModule.builder().bufferSize(100).build()
        assertEquals(500, tooSmall.configForTest().bufferSize)
        val tooBig = LogcatModule.builder().bufferSize(100_000).build()
        assertEquals(50_000, tooBig.configForTest().bufferSize)
    }

    @Test fun `builder clamps maxRecordingMb to range`() {
        val tooSmall = LogcatModule.builder().maxRecordingMb(0).build()
        assertEquals(1, tooSmall.configForTest().maxRecordingMb)
        val tooBig = LogcatModule.builder().maxRecordingMb(1000).build()
        assertEquals(200, tooBig.configForTest().maxRecordingMb)
    }

    @Test fun `defaultLevels respected`() {
        val m = LogcatModule.builder()
            .defaultLevels(setOf(LogLevel.ERROR, LogLevel.WARN))
            .build()
        assertEquals(setOf(LogLevel.ERROR, LogLevel.WARN), m.configForTest().defaultLevels)
    }
}
```

- [ ] **Step 3: Run test to confirm compile failure**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest 2>&1 | tail -10`
Expected: compile error — `LogcatModule` not found.

- [ ] **Step 4: Create `LogcatModule.kt`**

```kotlin
package com.debugtools.logcat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.core.content.FileProvider
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.logcat.data.LogLevel
import com.debugtools.logcat.presenter.LogcatPresenter
import com.debugtools.logcat.repository.LogRepository
import com.debugtools.logcat.source.LogcatParser
import com.debugtools.logcat.source.LogcatProducer
import com.debugtools.logcat.view.LogcatRootView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatModule private constructor(private val config: Config) : DebugModule {

    override val moduleId: String = "debugtools_logcat"
    override val tabTitle: String = "日志"

    private val repository = LogRepository(config)
    private val producer = LogcatProducer(repository, LogcatParser())
    private var presenter: LogcatPresenter? = null
    private var rootView: LogcatRootView? = null
    private var mainScope: CoroutineScope? = null
    private var appContext: Context? = null

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun createContentView(context: Context): View {
        val view = LogcatRootView(
            context = context,
            onLevelsChanged = { presenter?.setSelectedLevels(it) },
            onRecordToggle = { toggleRecord() },
            onClear = { repository.clear() },
            onRetryProducer = { producer.start() }
        )
        rootView = view
        presenter?.let { it.attachView(view) }
        return view
    }

    override fun getBriefItems(): List<BriefItem> {
        val state = repository.state.value
        val recent = state.lines.takeLast(200)
        val errors = recent.count { it.level == LogLevel.ERROR }
        val warns = recent.count { it.level == LogLevel.WARN }
        return listOf(
            BriefItem(
                text = "E $errors W $warns",
                color = if (errors > 0) android.graphics.Color.parseColor("#FC8181") else null
            )
        )
    }

    override fun onAttach(context: Context, storage: SettingsStorage) {
        appContext = context.applicationContext
        val main = CoroutineScope(Dispatchers.Main + SupervisorJob())
        mainScope = main
        producer.start()
        val p = LogcatPresenter(repository, config, main)
        presenter = p
        rootView?.let { p.attachView(it) }
    }

    override fun onDetach() {
        producer.stop()
        presenter?.detach()
        presenter = null
        mainScope?.cancel()
        mainScope = null
    }

    private fun toggleRecord() {
        val state = repository.state.value
        if (state.recording != null) {
            val file = repository.stopRecording() ?: return
            shareFile(file)
        } else {
            val ctx = appContext ?: return
            val dir = File(ctx.cacheDir, "debugtools").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "logcat-$ts.log")
            repository.startRecording(file)
        }
    }

    private fun shareFile(file: File) {
        val ctx = appContext ?: return
        val uri: Uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.debugtools.fileprovider", file)
        } catch (e: IllegalArgumentException) {
            // Integrator did not declare FileProvider; surface the file path via toast-equivalent log.
            android.util.Log.w("LogcatModule",
                "FileProvider authority not declared; recording saved at ${file.absolutePath}. " +
                "Add <provider> with authority \"${ctx.packageName}.debugtools.fileprovider\" to share automatically.")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "分享 logcat").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    internal fun configForTest(): Config = config

    class Builder {
        private var bufferSize: Int = 5000
        private var maxRecordingMb: Int = 20
        private var throttleMs: Long = 200L
        private var defaultLevels: Set<LogLevel> = LogLevel.values().toSet()

        fun bufferSize(n: Int) = apply { bufferSize = n.coerceIn(500, 50_000) }
        fun maxRecordingMb(mb: Int) = apply { maxRecordingMb = mb.coerceIn(1, 200) }
        fun throttleMs(ms: Long) = apply { throttleMs = ms.coerceAtLeast(0L) }
        fun defaultLevels(levels: Set<LogLevel>) = apply { defaultLevels = levels }
        fun build() = LogcatModule(
            Config(
                bufferSize = bufferSize,
                maxRecordingMb = maxRecordingMb,
                throttleMs = throttleMs,
                defaultLevels = defaultLevels
            )
        )
    }

    companion object {
        fun builder() = Builder()
    }
}
```

- [ ] **Step 5: Run tests + verify build**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :debugtools-logcat:testDebugUnitTest :debugtools-logcat:assembleDebug 2>&1 | tail -15`
Expected: 4 new LogcatModuleTest tests PASS + all earlier suites green; BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add debugtools-logcat/src/main/kotlin/com/debugtools/logcat/view/LogcatRootView.kt debugtools-logcat/src/main/kotlin/com/debugtools/logcat/LogcatModule.kt debugtools-logcat/src/test/kotlin/com/debugtools/logcat/LogcatModuleTest.kt && git commit -m "feat(logcat): add LogcatRootView and LogcatModule entry"
```

---

## Task 13: Sample App Integration

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/debugtools_file_paths.xml`

Wire `LogcatModule` into the demo and declare a FileProvider for share-intent export.

- [ ] **Step 1: Add dependency**

In `app/build.gradle.kts` `dependencies { }` block, after `implementation(project(":debugtools-perfmon"))`, add:

```kotlin
    implementation(project(":debugtools-logcat"))
```

- [ ] **Step 2: Declare FileProvider in `app/src/main/AndroidManifest.xml`**

Read the current manifest first. Inside the `<application>` element, add:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.debugtools.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/debugtools_file_paths" />
</provider>
```

- [ ] **Step 3: Create `app/src/main/res/xml/debugtools_file_paths.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="debugtools" path="debugtools/" />
</paths>
```

- [ ] **Step 4: Wire `LogcatModule` in `MainActivity.kt`**

Read `app/src/main/kotlin/com/debugtools/sample/MainActivity.kt` first.

Add import at the top:
```kotlin
import com.debugtools.logcat.LogcatModule
```

Add a new module field near `perfModule`:
```kotlin
    private val logcatModule = LogcatModule.builder().build()
```

In `initDebugTools()`, after `.register(perfModule)` add:
```kotlin
                .register(logcatModule)
```

- [ ] **Step 5: Build + test full project**

Run: `cd /Users/xianxiaoli/ClaudeProjects/DebugTools && ./gradlew :app:assembleDebug test 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL` for both.

- [ ] **Step 6: Commit**

```bash
cd /Users/xianxiaoli/ClaudeProjects/DebugTools && git add app/ && git commit -m "feat(app): integrate debugtools-logcat with FileProvider for share intent"
```

---

## Self-Review Notes

Verified against spec §1–§13:

| Spec section | Tasks |
|---|---|
| §2 Module layout | T1 |
| §3 Public API (Builder, ranges) | T12 |
| §4 Architecture / data flow | T6, T7, T8 |
| §5 LogcatProducer + LogcatParser | T4, T7 |
| §6 Data models | T2 |
| §7 LogRepository (ringbuffer, tick, recorder hook) | T6 |
| §8 LogRecorder + share intent | T5, T12 |
| §9 UI (toolbar, list, overlay, banner) | T9, T10, T11, T12 |
| §10 Performance budget (throttle, ringbuffer) | T6, T8 |
| §11 Error handling (subprocess die, FileProvider missing, cap exceeded) | T6, T7, T12 |
| §12 Testing strategy (subscribe-style, no LogcatProducer tests) | T6, T7 |
| §13 Known limitations | (documented in spec, no code) |

Type / signature consistency: all `LogLine`, `LogLevel`, `Snapshot`, `RecordingState`, `LogcatViewModel`, `Config` fields referenced in later tasks match definitions in T2/T3/T6/T8.

Placeholder scan: no TBD/TODO; every step has actual code. The only "scaffolding then refine" pattern is inside T6 step 3 (explained inline as a single-file collapse) — the final committed file should contain ONE clean `append`/`startRecording`/`stopRecording` set using the refined version.

Lessons baked in:
- Kotlin expression-body + return pre-flagged in plan header
- StateFlow tick pattern is in T6 by design (not an after-fix)
- LogcatProducer has no automated tests per spec §12 (real subprocess) — manual demo verification only
- LogcatModule.shareFile catches missing FileProvider → logs and saves anyway (no crash)
