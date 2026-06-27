# 双路音频录制 + 特性提取 + 上报 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `debugtools-audiomon` 增加「双路音频录制会话」能力：一次「开始录制→结束录制」同时落盘助手处理后音频（宿主推入）与 DebugTool 自采集音频，各自提取数值特性写盘，并通过宿主实现的上报接口交出会话产物。

**Architecture:** 纯函数特性提取器（`AudioFeatureExtractor` → `AudioFeatures`）做信号统计；`RecordingSessionController` 编排两路 WAV 写入 + 两个特性提取器 + 会话元数据落盘，产出 `AudioReportData`；`AudioReporter` 是宿主实现的上报接口；`AudioPresenter` 把麦克风采集流喂给控制器，`AudioMonitorModule` 暴露 `feedProcessedAudio` 与 `reporter` 注入点。信号/落盘逻辑全部下沉到纯 JVM 可测的类，Android 耦合层（Presenter/Module/View）只做粘合。

**Tech Stack:** Kotlin, Android (`compileSdk 34`, `minSdk 26`), kotlinx-coroutines, Android 内置 `org.json`（生产）+ `org.json:json` 测试依赖（单测可跑），JUnit4。

**参考规格：** `docs/superpowers/specs/2026-06-28-audiomon-dual-stream-design.md`

---

## File Structure

新增：
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/audio/AudioFeatures.kt` — 特性数据类 + `org.json` 序列化
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/audio/AudioFeatureExtractor.kt` — 流式特性累积器（纯函数）
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/report/AudioReporter.kt` — 上报接口
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/report/AudioReportData.kt` — 上报数据载体
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/session/RecordingSessionController.kt` — 会话编排 + 落盘
- 测试：`AudioFeatureExtractorTest.kt`、`RecordingSessionControllerTest.kt`

修改：
- `debugtools-audiomon/build.gradle.kts` — 增加 `testImplementation("org.json:json:20231013")`
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/presenter/AudioPresenter.kt` — 双路编排、会话落盘、上报触发
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/AudioMonitorModule.kt` — `reporter` 构造参、`feedProcessedAudio`、设置项调整
- `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AudioMonitorView.kt` — 按钮文案、最近会话 + 上报按钮

---

## Task 1: 测试依赖 + AudioFeatures 数据类与序列化

**Files:**
- Modify: `debugtools-audiomon/build.gradle.kts`
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/audio/AudioFeatures.kt`
- Test: `debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/audio/AudioFeaturesTest.kt`

> **背景：** Android 的 `org.json` 打包在 `android.jar` 里是空壳，纯 JVM 单测调用会抛 “not mocked”。加 `testImplementation("org.json:json:20231013")` 让真实实现在测试 classpath 顶替空壳，生产代码仍用平台自带的——不引入任何生产依赖。

- [ ] **Step 1: 加测试依赖**

修改 `debugtools-audiomon/build.gradle.kts` 的 `dependencies` 块，追加一行：

```kotlin
    testImplementation("org.json:json:20231013")
```

加完后该块为：

```kotlin
dependencies {
    implementation(project(":debugtools-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.json:json:20231013")
}
```

- [ ] **Step 2: 写失败测试**

创建 `AudioFeaturesTest.kt`：

```kotlin
package com.debugtools.audiomon.audio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFeaturesTest {

    private fun sample() = AudioFeatures(
        durationMs = 1000L,
        sampleCount = 16000L,
        avgRms = 0.25f,
        peakAmplitude = 0.9f,
        avgDb = -12.0f,
        peakDb = -0.9f,
        zeroCrossingRate = 0.05f,
        silenceRatio = 0.3f,
        activeRatio = 0.7f,
        dominantFreq = 440.0f,
        spectralCentroid = 1200.0f,
        bandEnergy = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.3f, 0.2f, 0.1f, 0.05f),
        rmsSeries = floatArrayOf(0.2f, 0.3f, 0.25f),
        dbSeries = floatArrayOf(-14f, -10f, -12f)
    )

    @Test
    fun `toJson contains all feature groups`() {
        val json = sample().toJson()
        assertEquals(1000L, json.getLong("durationMs"))
        assertEquals(440.0, json.getDouble("dominantFreq"), 1e-3)
        assertEquals(8, json.getJSONArray("bandEnergy").length())
        assertEquals(3, json.getJSONArray("rmsSeries").length())
        assertEquals(0.7, json.getDouble("activeRatio"), 1e-3)
    }

    @Test
    fun `summaryJson exposes only overview fields`() {
        val s = sample().summaryJson()
        assertEquals(1000L, s.getLong("durationMs"))
        assertEquals(-12.0, s.getDouble("avgDb"), 1e-3)
        assertEquals(-0.9, s.getDouble("peakDb"), 1e-3)
        assertEquals(0.7, s.getDouble("activeRatio"), 1e-3)
        assertTrue(!s.has("rmsSeries"))
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioFeaturesTest*"`
Expected: FAIL（`AudioFeatures` 未定义 / 编译失败）。

- [ ] **Step 4: 实现 AudioFeatures**

创建 `AudioFeatures.kt`：

```kotlin
package com.debugtools.audiomon.audio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Aggregated numerical features for one recorded audio stream.
 *
 * Pure data holder. Computed by [AudioFeatureExtractor]; serialized to a
 * `*.features.json` file by [toJson]. [summaryJson] returns the small subset
 * embedded into `session.json` for at-a-glance display.
 */
data class AudioFeatures(
    // basic amplitude
    val durationMs: Long,
    val sampleCount: Long,
    val avgRms: Float,
    val peakAmplitude: Float,
    val avgDb: Float,
    val peakDb: Float,
    // silence / activity
    val zeroCrossingRate: Float,
    val silenceRatio: Float,
    val activeRatio: Float,
    // spectral
    val dominantFreq: Float,
    val spectralCentroid: Float,
    val bandEnergy: FloatArray,
    // per-frame timeseries
    val rmsSeries: FloatArray,
    val dbSeries: FloatArray
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("durationMs", durationMs)
        put("sampleCount", sampleCount)
        put("avgRms", avgRms.toDouble())
        put("peakAmplitude", peakAmplitude.toDouble())
        put("avgDb", avgDb.toDouble())
        put("peakDb", peakDb.toDouble())
        put("zeroCrossingRate", zeroCrossingRate.toDouble())
        put("silenceRatio", silenceRatio.toDouble())
        put("activeRatio", activeRatio.toDouble())
        put("dominantFreq", dominantFreq.toDouble())
        put("spectralCentroid", spectralCentroid.toDouble())
        put("bandEnergy", bandEnergy.toJsonArray())
        put("rmsSeries", rmsSeries.toJsonArray())
        put("dbSeries", dbSeries.toJsonArray())
    }

    fun summaryJson(): JSONObject = JSONObject().apply {
        put("durationMs", durationMs)
        put("avgDb", avgDb.toDouble())
        put("peakDb", peakDb.toDouble())
        put("activeRatio", activeRatio.toDouble())
    }

    private fun FloatArray.toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (v in this) arr.put(v.toDouble())
        return arr
    }

    // data class with array fields: equals/hashCode unused by app logic; omit override.
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioFeaturesTest*"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add debugtools-audiomon/build.gradle.kts \
  debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/audio/AudioFeatures.kt \
  debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/audio/AudioFeaturesTest.kt
git commit -m "feat(audiomon): add AudioFeatures data class with JSON serialization"
```

---

## Task 2: AudioFeatureExtractor 流式特性提取器

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/audio/AudioFeatureExtractor.kt`
- Test: `debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/audio/AudioFeatureExtractorTest.kt`

> 复用现有 `FftProcessor.computeRms`（任意长度，返回 0..1）与 `FftProcessor.computeMagnitudes`（要求长度==fftSize，返回归一化 0..1 dB 谱）。频谱类特征基于归一化谱计算，对 debug 用途足够；`dominantFreq` 取峰值 bin，结果与原始谱一致。

- [ ] **Step 1: 写失败测试**

创建 `AudioFeatureExtractorTest.kt`：

```kotlin
package com.debugtools.audiomon.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class AudioFeatureExtractorTest {

    private val sampleRate = 16000
    private val fftSize = 1024

    /** PCM16 sine: [cycles] periods across [size] samples at amplitude [amp] (0..1). */
    private fun sine(size: Int, cycles: Int, amp: Double = 0.5): ShortArray =
        ShortArray(size) { i ->
            (Short.MAX_VALUE * amp * sin(2.0 * PI * cycles * i / size)).roundToInt().toShort()
        }

    private fun extractor(thresholdDb: Float = -50f) =
        AudioFeatureExtractor(sampleRate, fftSize, thresholdDb)

    @Test
    fun `empty input yields all-zero features without crashing`() {
        val f = extractor().build()
        assertEquals(0L, f.sampleCount)
        assertEquals(0L, f.durationMs)
        assertEquals(0f, f.avgRms, 0f)
        assertEquals(AudioFeatureExtractor.BAND_COUNT, f.bandEnergy.size)
    }

    @Test
    fun `silence is mostly silent frames with very low dB`() {
        val ex = extractor()
        repeat(8) { ex.feed(ShortArray(fftSize)) }
        val f = ex.build()
        assertEquals(1.0f, f.silenceRatio, 1e-3f)
        assertEquals(0.0f, f.activeRatio, 1e-3f)
        assertTrue("avgDb=${f.avgDb}", f.avgDb < -60f)
    }

    @Test
    fun `duration is derived from sample count and rate`() {
        val ex = extractor()
        repeat(16) { ex.feed(sine(fftSize, 64, 0.5)) } // 16 * 1024 = 16384 samples
        val f = ex.build()
        assertEquals(16384L, f.sampleCount)
        assertEquals(16384L * 1000 / sampleRate, f.durationMs)
    }

    @Test
    fun `dominant frequency tracks a pure tone`() {
        // 64 cycles over a 1024-sample window -> bin 64 -> 64 * 16000 / 1024 = 1000 Hz.
        val ex = extractor()
        repeat(8) { ex.feed(sine(fftSize, 64, amp = 0.05)) }
        val f = ex.build()
        val expected = 64f * sampleRate / fftSize
        assertEquals(expected, f.dominantFreq, expected * 0.1f)
    }

    @Test
    fun `loud tone is mostly active frames`() {
        val ex = extractor(thresholdDb = -50f)
        repeat(8) { ex.feed(sine(fftSize, 64, amp = 0.8)) }
        val f = ex.build()
        assertTrue("activeRatio=${f.activeRatio}", f.activeRatio > 0.9f)
        assertEquals(8, f.rmsSeries.size)
        assertEquals(8, f.dbSeries.size)
    }

    @Test
    fun `zero crossing rate is roughly two per cycle`() {
        // 64 cycles over 1024 samples -> ~128 crossings -> rate ~ 128/1024 = 0.125.
        val ex = extractor()
        ex.feed(sine(fftSize, 64, amp = 0.5))
        val f = ex.build()
        assertEquals(0.125f, f.zeroCrossingRate, 0.02f)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioFeatureExtractorTest*"`
Expected: FAIL（`AudioFeatureExtractor` 未定义）。

- [ ] **Step 3: 实现 AudioFeatureExtractor**

创建 `AudioFeatureExtractor.kt`：

```kotlin
package com.debugtools.audiomon.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * Streaming numerical-feature accumulator for a single PCM16 mono stream.
 *
 * Call [feed] per frame during recording (constant memory — running sums plus
 * one float per frame for the timeseries), then [build] to aggregate. Pure JVM;
 * no Android dependencies. Frame size should equal [fftSize] for spectral
 * features to accumulate; shorter trailing frames still count toward amplitude
 * and timeseries stats.
 */
class AudioFeatureExtractor(
    private val sampleRate: Int,
    private val fftSize: Int,
    private val silenceThresholdDb: Float = -50f,
    private val bandCount: Int = BAND_COUNT
) {
    companion object {
        const val BAND_COUNT = 8
        private const val MIN_DB = -90f
        private fun ampToDb(amp: Float): Float =
            if (amp <= 1e-7f) MIN_DB else (20f * log10(amp)).coerceAtLeast(MIN_DB)
    }

    private var sampleCount = 0L
    private var sumSquares = 0.0
    private var peakAmplitude = 0f
    private var zeroCrossings = 0L
    private var hasLastSample = false
    private var lastSamplePositive = false

    private var silentFrames = 0
    private var activeFrames = 0
    private val rmsSeries = ArrayList<Float>()
    private val dbSeries = ArrayList<Float>()

    private val halfSize = fftSize / 2
    private val magAccum = DoubleArray(halfSize)
    private var spectralFrames = 0

    fun feed(frame: ShortArray) {
        if (frame.isEmpty()) return

        // amplitude + zero crossings
        for (s in frame) {
            val f = s.toFloat() / Short.MAX_VALUE
            sumSquares += f.toDouble() * f
            val a = abs(f)
            if (a > peakAmplitude) peakAmplitude = a
            val positive = f >= 0f
            if (hasLastSample && positive != lastSamplePositive) zeroCrossings++
            lastSamplePositive = positive
            hasLastSample = true
        }
        sampleCount += frame.size

        // per-frame timeseries + silence/activity
        val frameRms = FftProcessor.computeRms(frame)
        val frameDb = ampToDb(frameRms)
        rmsSeries.add(frameRms)
        dbSeries.add(frameDb)
        if (frameDb < silenceThresholdDb) silentFrames++ else activeFrames++

        // spectral (full frames only)
        if (frame.size == fftSize) {
            val mags = FftProcessor.computeMagnitudes(frame, fftSize)
            for (i in 0 until halfSize) magAccum[i] += mags[i]
            spectralFrames++
        }
    }

    fun build(): AudioFeatures {
        val avgRms = if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount).toFloat() else 0f
        val avgDb = ampToDb(avgRms)
        val peakDb = ampToDb(peakAmplitude)
        val durationMs = if (sampleRate > 0) sampleCount * 1000 / sampleRate else 0L
        val zcr = if (sampleCount > 0) zeroCrossings.toFloat() / sampleCount else 0f
        val frameTotal = silentFrames + activeFrames
        val silenceRatio = if (frameTotal > 0) silentFrames.toFloat() / frameTotal else 0f
        val activeRatio = if (frameTotal > 0) activeFrames.toFloat() / frameTotal else 0f

        val avgMags = FloatArray(halfSize) {
            if (spectralFrames > 0) (magAccum[it] / spectralFrames).toFloat() else 0f
        }
        val dominantBin = avgMags.indices.maxByOrNull { avgMags[it] } ?: 0
        val dominantFreq = if (fftSize > 0) dominantBin.toFloat() * sampleRate / fftSize else 0f
        val spectralCentroid = computeCentroid(avgMags)
        val bandEnergy = computeBands(avgMags)

        return AudioFeatures(
            durationMs = durationMs,
            sampleCount = sampleCount,
            avgRms = avgRms,
            peakAmplitude = peakAmplitude,
            avgDb = avgDb,
            peakDb = peakDb,
            zeroCrossingRate = zcr,
            silenceRatio = silenceRatio,
            activeRatio = activeRatio,
            dominantFreq = dominantFreq,
            spectralCentroid = spectralCentroid,
            bandEnergy = bandEnergy,
            rmsSeries = rmsSeries.toFloatArray(),
            dbSeries = dbSeries.toFloatArray()
        )
    }

    private fun computeCentroid(mags: FloatArray): Float {
        var weighted = 0.0
        var total = 0.0
        for (i in mags.indices) {
            val freq = if (fftSize > 0) i.toDouble() * sampleRate / fftSize else 0.0
            weighted += freq * mags[i]
            total += mags[i]
        }
        return if (total > 0) (weighted / total).toFloat() else 0f
    }

    private fun computeBands(mags: FloatArray): FloatArray {
        val bands = FloatArray(bandCount)
        if (mags.isEmpty()) return bands
        for (i in mags.indices) {
            val band = (i * bandCount / mags.size).coerceIn(0, bandCount - 1)
            bands[band] += mags[i]
        }
        return bands
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioFeatureExtractorTest*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/audio/AudioFeatureExtractor.kt \
  debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/audio/AudioFeatureExtractorTest.kt
git commit -m "feat(audiomon): add streaming AudioFeatureExtractor"
```

---

## Task 3: 上报接口 AudioReporter + AudioReportData

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/report/AudioReporter.kt`
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/report/AudioReportData.kt`

> 纯接口 + 数据载体，无行为逻辑，跳过 TDD 红绿环（无可测行为）；其正确性由 Task 4 的会话测试覆盖（验证产物文件齐全）。

- [ ] **Step 1: 创建 AudioReportData**

```kotlin
package com.debugtools.audiomon.report

import java.io.File

/**
 * Artifacts of one completed recording session handed to [AudioReporter].
 *
 * Stream A files are null when the host never pushed processed audio during
 * the session. [metadata] is the session.json describing the whole session.
 */
data class AudioReportData(
    val sessionId: String,
    val sessionDir: File,
    val streamBWav: File?,
    val streamBFeatures: File?,
    val streamAWav: File?,
    val streamAFeatures: File?,
    val metadata: File
)
```

- [ ] **Step 2: 创建 AudioReporter**

```kotlin
package com.debugtools.audiomon.report

/**
 * Host-implemented network reporting hook for recorded audio sessions.
 *
 * The SDK invokes [report] on an IO thread once a session's artifacts are
 * written to disk. The host decides how to upload (OkHttp / proprietary
 * channel / not at all). Exceptions thrown here are caught by the SDK and
 * surfaced in the panel; they do not affect the recording pipeline.
 */
interface AudioReporter {
    fun report(session: com.debugtools.audiomon.report.AudioReportData)
}
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :debugtools-audiomon:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/report/
git commit -m "feat(audiomon): add AudioReporter interface and AudioReportData"
```

---

## Task 4: RecordingSessionController 会话编排与落盘

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/session/RecordingSessionController.kt`
- Test: `debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/session/RecordingSessionControllerTest.kt`

> 复用现有 `WavFileWriter`（纯 `java.io`，JVM 可测）。`clock` 与 `sessionIdProvider` 作为可注入参数以便测试确定化。方法加 `@Synchronized`：B 路来自采集协程线程，A 路来自宿主音频线程，`finish` 来自主线程，需互斥保护惰性创建的 A 路对象。

- [ ] **Step 1: 写失败测试**

创建 `RecordingSessionControllerTest.kt`：

```kotlin
package com.debugtools.audiomon.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class RecordingSessionControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sampleRate = 16000
    private val fftSize = 1024

    private fun sine(size: Int, cycles: Int, amp: Double = 0.5): ShortArray =
        ShortArray(size) { i ->
            (Short.MAX_VALUE * amp * sin(2.0 * PI * cycles * i / size)).roundToInt().toShort()
        }

    private fun controller(root: java.io.File) = RecordingSessionController(
        rootDir = root,
        sampleRate = sampleRate,
        fftSize = fftSize,
        silenceThresholdDb = -50f,
        clock = { 1_000L },
        sessionIdProvider = { "testsession" }
    )

    @Test
    fun `finish without start returns null`() {
        assertNull(controller(tmp.root).finish())
    }

    @Test
    fun `both streams produce wav and feature files and session json`() {
        val c = controller(tmp.root)
        c.start()
        repeat(8) {
            c.feedStreamB(sine(fftSize, 64, 0.5))
            c.feedStreamA(sine(fftSize, 64, 0.3))
        }
        val report = c.finish()
        assertNotNull(report)
        report!!
        assertEquals("testsession", report.sessionId)
        assertTrue(report.streamBWav!!.exists() && report.streamBWav!!.length() > 44)
        assertTrue(report.streamAWav!!.exists() && report.streamAWav!!.length() > 44)
        assertTrue(report.streamBFeatures!!.exists())
        assertTrue(report.streamAFeatures!!.exists())
        assertTrue(report.metadata.exists())

        val json = JSONObject(report.metadata.readText())
        assertEquals("testsession", json.getString("sessionId"))
        assertEquals(sampleRate, json.getInt("sampleRate"))
        val streams = json.getJSONObject("streams")
        assertTrue(streams.getJSONObject("streamB").getBoolean("present"))
        assertTrue(streams.getJSONObject("streamA").getBoolean("present"))
        assertTrue(streams.getJSONObject("streamB").getJSONObject("summary").has("avgDb"))
    }

    @Test
    fun `stream A absent when host never feeds it`() {
        val c = controller(tmp.root)
        c.start()
        repeat(4) { c.feedStreamB(sine(fftSize, 64, 0.5)) }
        val report = c.finish()!!
        assertNull(report.streamAWav)
        assertNull(report.streamAFeatures)
        assertFalse(java.io.File(report.sessionDir, "streamA.wav").exists())

        val streams = JSONObject(report.metadata.readText()).getJSONObject("streams")
        assertFalse(streams.getJSONObject("streamA").getBoolean("present"))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :debugtools-audiomon:test --tests "*RecordingSessionControllerTest*"`
Expected: FAIL（`RecordingSessionController` 未定义）。

- [ ] **Step 3: 实现 RecordingSessionController**

创建 `RecordingSessionController.kt`：

```kotlin
package com.debugtools.audiomon.session

import com.debugtools.audiomon.audio.AudioFeatureExtractor
import com.debugtools.audiomon.audio.AudioFeatures
import com.debugtools.audiomon.audio.WavFileWriter
import com.debugtools.audiomon.report.AudioReportData
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Orchestrates one recording session: two parallel PCM16 streams written to
 * WAV plus per-stream [AudioFeatureExtractor], finalized into a session
 * directory with a `session.json` and returned as [AudioReportData].
 *
 * Stream B (DebugTool mic capture) is fed from the capture coroutine; stream A
 * (host's processed audio) is pushed from the host's audio thread and its
 * writer/extractor are created lazily on the first frame. All public methods
 * are synchronized to guard the lazily-created stream-A state across threads.
 *
 * [clock] and [sessionIdProvider] are injectable for deterministic tests.
 */
class RecordingSessionController(
    private val rootDir: File,
    private val sampleRate: Int,
    private val fftSize: Int,
    private val silenceThresholdDb: Float,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val sessionIdProvider: () -> String = { defaultSessionId() }
) {
    companion object {
        private val ID_DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        private const val SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

        private fun defaultSessionId(): String {
            val ts = ID_DATE_FORMAT.format(Date())
            val suffix = (1..4).map { SUFFIX_CHARS[Random.nextInt(SUFFIX_CHARS.length)] }.joinToString("")
            return "${ts}_$suffix"
        }
    }

    private var sessionId: String? = null
    private var sessionDir: File? = null
    private var startTime = 0L

    private var bWriter: WavFileWriter? = null
    private var bExtractor: AudioFeatureExtractor? = null
    private var aWriter: WavFileWriter? = null
    private var aExtractor: AudioFeatureExtractor? = null

    val currentSessionDir: File? @Synchronized get() = sessionDir

    @Synchronized
    fun start() {
        val id = sessionIdProvider()
        val dir = File(rootDir, id).apply { mkdirs() }
        sessionId = id
        sessionDir = dir
        startTime = clock()

        bWriter = WavFileWriter(File(dir, "streamB.wav"), sampleRate).also { it.open() }
        bExtractor = newExtractor()
    }

    @Synchronized
    fun feedStreamB(frame: ShortArray) {
        bWriter?.writeSamples(frame)
        bExtractor?.feed(frame)
    }

    @Synchronized
    fun feedStreamA(frame: ShortArray) {
        val dir = sessionDir ?: return
        if (aWriter == null) {
            aWriter = WavFileWriter(File(dir, "streamA.wav"), sampleRate).also { it.open() }
            aExtractor = newExtractor()
        }
        aWriter?.writeSamples(frame)
        aExtractor?.feed(frame)
    }

    /** Finalize files and return the session artifacts, or null if not started. */
    @Synchronized
    fun finish(): AudioReportData? {
        val id = sessionId ?: return null
        val dir = sessionDir ?: return null
        val endTime = clock()

        bWriter?.close()
        aWriter?.close()

        val bFeatures = bExtractor?.build()
        val aFeatures = aExtractor?.build()

        val bFeaturesFile = bFeatures?.let { writeFeatures(dir, "streamB.features.json", it) }
        val aFeaturesFile = aFeatures?.let { writeFeatures(dir, "streamA.features.json", it) }

        val aPresent = aExtractor != null
        val metadata = writeSessionJson(dir, id, endTime, bFeatures, aFeatures, aPresent)

        val report = AudioReportData(
            sessionId = id,
            sessionDir = dir,
            streamBWav = File(dir, "streamB.wav").takeIf { it.exists() },
            streamBFeatures = bFeaturesFile,
            streamAWav = if (aPresent) File(dir, "streamA.wav").takeIf { it.exists() } else null,
            streamAFeatures = aFeaturesFile,
            metadata = metadata
        )

        reset()
        return report
    }

    private fun newExtractor() =
        AudioFeatureExtractor(sampleRate, fftSize, silenceThresholdDb)

    private fun writeFeatures(dir: File, name: String, features: AudioFeatures): File {
        val file = File(dir, name)
        file.writeText(features.toJson().toString())
        return file
    }

    private fun writeSessionJson(
        dir: File,
        id: String,
        endTime: Long,
        bFeatures: AudioFeatures?,
        aFeatures: AudioFeatures?,
        aPresent: Boolean
    ): File {
        val streams = JSONObject().apply {
            put("streamB", streamNode(present = true, wav = "streamB.wav",
                features = "streamB.features.json", f = bFeatures))
            put("streamA", streamNode(present = aPresent,
                wav = if (aPresent) "streamA.wav" else JSONObject.NULL,
                features = if (aPresent) "streamA.features.json" else JSONObject.NULL,
                f = aFeatures))
        }
        val root = JSONObject().apply {
            put("sessionId", id)
            put("startTime", startTime)
            put("endTime", endTime)
            put("durationMs", endTime - startTime)
            put("sampleRate", sampleRate)
            put("streams", streams)
        }
        val file = File(dir, "session.json")
        file.writeText(root.toString())
        return file
    }

    private fun streamNode(present: Boolean, wav: Any?, features: Any?, f: AudioFeatures?): JSONObject =
        JSONObject().apply {
            put("present", present)
            put("wav", wav ?: JSONObject.NULL)
            put("features", features ?: JSONObject.NULL)
            put("summary", f?.summaryJson() ?: JSONObject.NULL)
        }

    private fun reset() {
        sessionId = null
        sessionDir = null
        bWriter = null; bExtractor = null
        aWriter = null; aExtractor = null
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :debugtools-audiomon:test --tests "*RecordingSessionControllerTest*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/session/RecordingSessionController.kt \
  debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/session/RecordingSessionControllerTest.kt
git commit -m "feat(audiomon): add RecordingSessionController orchestrating dual-stream session"
```

---

## Task 5: AudioPresenter 双路编排与上报触发

**Files:**
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/presenter/AudioPresenter.kt`
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/presenter/AudioView.kt`

> Android 协程/AudioRecord 耦合层，逻辑已下沉到 Task 2/4（已测）。本任务为粘合，无新单测；通过编译验证。`AudioRecorderWrapper` 不再自己存盘（`saveEnabled=false`），B 路 WAV 由控制器写。

- [ ] **Step 1: 查看现有 AudioView 接口**

先读 `AudioView.kt` 确认现有方法（`showStatus`/`showWaveform`/`showSpectrum`/`showMonitoringState`）。本任务给它新增一个回调用于展示最近会话：

在 `AudioView` 接口中新增方法（保留原有所有方法）：

```kotlin
    /** Show the most recent finished session; reporterConfigured gates the upload button. */
    fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean)
```

- [ ] **Step 2: 重写 AudioPresenter**

把 `AudioPresenter.kt` 整体替换为：

```kotlin
package com.debugtools.audiomon.presenter

import com.debugtools.audiomon.audio.AudioRecorderWrapper
import com.debugtools.audiomon.audio.FftProcessor
import com.debugtools.audiomon.report.AudioReportData
import com.debugtools.audiomon.report.AudioReporter
import com.debugtools.audiomon.session.RecordingSessionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.sample
import java.io.File

/**
 * MVP Presenter for dual-stream audio recording.
 *
 * Stream B (mic) is captured by [AudioRecorderWrapper] and fed to both the live
 * oscilloscope/FFT view and the [RecordingSessionController]. Stream A (the
 * host's processed audio) arrives via [feedProcessedAudio]. On stop, the
 * controller writes both WAVs + features + session.json, and the session is
 * optionally auto-reported.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AudioPresenter(
    private val sampleRate: Int = AudioRecorderWrapper.DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = AudioRecorderWrapper.DEFAULT_FFT_SIZE,
    private val rootDir: File,
    private val silenceThresholdDb: Float = -50f,
    private val autoReport: Boolean = false,
    private val reporter: AudioReporter? = null
) {
    private var view: AudioView? = null
    private var recorder: AudioRecorderWrapper? = null
    private var controller: RecordingSessionController? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null
    private var isRecording = false
    private var lastSession: AudioReportData? = null

    val monitoring: Boolean get() = isRecording

    fun attach(view: AudioView) {
        this.view = view
        view.showStatus("点击按钮开始录制\n保存目录: ${rootDir.absolutePath}")
    }

    fun detach() {
        stopRecording()
        view = null
        scope.cancel()
    }

    /** Host pushes processed-audio PCM16 frames; ignored unless a session is active. */
    fun feedProcessedAudio(frame: ShortArray) {
        controller?.feedStreamA(frame)
    }

    fun startRecording() {
        val v = view ?: return
        if (collectJob?.isActive == true) return

        val ctrl = RecordingSessionController(rootDir, sampleRate, fftSize, silenceThresholdDb)
        ctrl.start()
        controller = ctrl

        val rec = AudioRecorderWrapper(sampleRate, fftSize, saveDir = null, saveEnabled = false)
        recorder = rec
        val result = rec.start()
        if (result.isFailure) {
            v.showStatus("❌ ${result.exceptionOrNull()?.message}")
            ctrl.finish()
            controller = null
            recorder = null
            return
        }

        isRecording = true
        v.showMonitoringState(true)
        v.showStatus("🎙️ 录制中 (${sampleRate}Hz)\n会话: ${ctrl.currentSessionDir?.name}")

        collectJob = scope.launch {
            rec.audioStream
                .sample(60) // ~16 FPS UI updates; controller still receives every frame
                .collect { pcmBuffer ->
                    controller?.feedStreamB(pcmBuffer)

                    val floatSamples = FloatArray(pcmBuffer.size) {
                        pcmBuffer[it].toFloat() / Short.MAX_VALUE
                    }
                    val rms = FftProcessor.computeRms(pcmBuffer)
                    val spectrum = if (pcmBuffer.size == fftSize) {
                        FftProcessor.computeMagnitudes(pcmBuffer, fftSize)
                    } else null

                    withContext(Dispatchers.Main) {
                        view?.showWaveform(floatSamples, rms)
                        if (spectrum != null) view?.showSpectrum(spectrum)
                    }
                }
        }
    }

    fun stopRecording() {
        collectJob?.cancel()
        collectJob = null

        recorder?.stop()
        recorder?.destroy()
        recorder = null

        val report = controller?.finish()
        controller = null
        isRecording = false
        lastSession = report
        view?.showMonitoringState(false)

        if (report != null) {
            val aState = if (report.streamAWav != null) "A路+B路" else "仅B路"
            val sizeKb = (report.streamBWav?.length() ?: 0L) / 1024
            val summary = "$aState | B路 ${sizeKb}KB"
            view?.showStatus("✅ 会话完成: ${report.sessionId}")
            view?.showLastSession(report.sessionId, summary, reporter != null)
            if (autoReport && reporter != null) {
                dispatchReport(report)
            }
        } else {
            view?.showStatus("已停止")
        }
    }

    /** Manually trigger upload of the most recent session (called from the UI button). */
    fun reportLastSession() {
        val report = lastSession ?: return
        if (reporter == null) {
            view?.showStatus("⚠️ 未配置上报接口")
            return
        }
        dispatchReport(report)
    }

    private fun dispatchReport(report: AudioReportData) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { reporter?.report(report) }.isSuccess
            withContext(Dispatchers.Main) {
                view?.showStatus(if (ok) "📤 已上报: ${report.sessionId}" else "❌ 上报失败: ${report.sessionId}")
            }
        }
    }

    fun toggleMonitoring() {
        if (isRecording) stopRecording() else startRecording()
    }
}
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :debugtools-audiomon:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（`AudioMonitorView` 尚未实现 `showLastSession` 会在 Task 7 报错——所以本步骤只编译 main 源集里已存在的；若因 View 未实现接口而失败，先在 Task 7 补齐后再整体编译。见下方说明。）

> 说明：`AudioMonitorView`（实现 `AudioView`）需要实现新方法 `showLastSession`。为保持每个任务可独立编译，**在本任务 Step 2 之后立刻给 `AudioMonitorView` 加一个最小空实现**（Task 7 再做完整 UI）：临时在 `AudioMonitorView` 中加

```kotlin
    override fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean) { /* Task 7 */ }
```

- [ ] **Step 4: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/presenter/
git commit -m "feat(audiomon): rewire AudioPresenter for dual-stream sessions and reporting"
```

---

## Task 6: AudioMonitorModule —— reporter 注入、feedProcessedAudio、设置项

**Files:**
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/AudioMonitorModule.kt`

- [ ] **Step 1: 改造 AudioMonitorModule**

把 `AudioMonitorModule.kt` 替换为（要点：构造参 `reporter`；`feedProcessedAudio` 转发给 presenter；`onAttach` 读取新设置项并传入 rootDir/threshold/autoReport；`buildSettings` 移除 `save_enabled`、新增 `auto_report` 与 `silence_threshold_db`）：

```kotlin
package com.debugtools.audiomon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Environment
import android.view.View
import com.debugtools.audiomon.presenter.AudioPresenter
import com.debugtools.audiomon.report.AudioReporter
import com.debugtools.audiomon.view.AudioMonitorView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.core.settings.SettingItem
import java.io.File

/**
 * Debug module for dual-stream audio recording.
 *
 * Captures DebugTool's own mic (stream B) and accepts the host's processed
 * audio via [feedProcessedAudio] (stream A). On stop, both streams are written
 * to a session directory with per-stream numerical features, and optionally
 * reported via the host-supplied [AudioReporter].
 *
 * Note: [feedProcessedAudio] requires the host to hold this module instance in
 * the same process — supported in ATTACHED mode only.
 *
 * ```kotlin
 * val audio = AudioMonitorModule(reporter = myReporter)
 * DebugTools.builder(context).register(audio).build()
 * // in the host audio callback: audio.feedProcessedAudio(frame)
 * ```
 */
class AudioMonitorModule(
    private val reporter: AudioReporter? = null
) : DebugModule {

    override val moduleId: String = "audiomon"
    override val tabTitle: String = "音频监控"

    private var presenter: AudioPresenter? = null
    private var monitorView: AudioMonitorView? = null
    private var appContext: Context? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        this.appContext = context.applicationContext
        val sampleRate = storage.getString("sample_rate", "16000").toIntOrNull() ?: 16000
        val rootPath = storage.getString("save_dir", getDefaultSaveDir(context).absolutePath)
        val autoReport = storage.getBoolean("auto_report", false)
        val silenceThresholdDb = storage.getString("silence_threshold_db", "-50").toFloatOrNull() ?: -50f

        val rootDir = File(rootPath).also { if (!it.exists()) it.mkdirs() }

        presenter = AudioPresenter(
            sampleRate = sampleRate,
            rootDir = rootDir,
            silenceThresholdDb = silenceThresholdDb,
            autoReport = autoReport,
            reporter = reporter
        )
    }

    override fun onDetach() {
        presenter?.detach()
        presenter = null
        monitorView = null
        appContext = null
    }

    /** Push host-processed PCM16 frames (stream A). No-op outside an active session. */
    fun feedProcessedAudio(frame: ShortArray) {
        presenter?.feedProcessedAudio(frame)
    }

    override fun createContentView(context: Context): View {
        return AudioMonitorView(context).also { view ->
            monitorView = view
            presenter?.attach(view)

            view.setToggleListener {
                if (!hasRecordPermission(context)) {
                    view.showStatus("⚠️ 需要录音权限 — 请先在宿主 Activity 中授权")
                    return@setToggleListener
                }
                presenter?.toggleMonitoring()
            }
            view.setReportListener { presenter?.reportLastSession() }

            if (!hasRecordPermission(context)) {
                view.showStatus("⚠️ 需要录音权限 — 请先在宿主 Activity 中授权")
            }
        }
    }

    override fun buildSettings(): List<SettingGroup> {
        val defaultDir = appContext?.let { getDefaultSaveDir(it).absolutePath } ?: "/sdcard/DebugTools/audio"
        return listOf(
            SettingGroup(
                title = "音频设置",
                items = listOf(
                    SettingItem.SingleSelect(
                        key = "sample_rate",
                        label = "采样率",
                        options = listOf("8000", "16000", "44100"),
                        default = "16000",
                        description = "麦克风采样率 (Hz)，修改后需重启模块生效"
                    ),
                    SettingItem.EditText(
                        key = "save_dir",
                        label = "录制保存目录",
                        default = defaultDir,
                        hint = "例如: /sdcard/DebugTools/audio",
                        description = "会话(WAV+特性+session.json)的根目录，修改后下次录制生效"
                    ),
                    SettingItem.SingleSelect(
                        key = "silence_threshold_db",
                        label = "静音阈值(dB)",
                        options = listOf("-40", "-50", "-60"),
                        default = "-50",
                        description = "低于该 dB 的帧判为静音，用于静音/活动占比统计"
                    ),
                    SettingItem.Toggle(
                        key = "auto_report",
                        label = "结束后自动上报",
                        default = false,
                        description = "录制结束后自动调用宿主上报接口（未配置接口则忽略）"
                    )
                )
            )
        )
    }

    override fun getBriefItems(): List<BriefItem> {
        val active = presenter?.monitoring == true
        return listOf(
            BriefItem(
                text = if (active) "🎙️ 录制中" else "⏸ 已停止",
                color = if (active) Color.parseColor("#48BB78") else Color.parseColor("#A0AEC0")
            )
        )
    }

    private fun hasRecordPermission(context: Context): Boolean {
        return context.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getDefaultSaveDir(context: Context): File {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
        return externalDir ?: File(context.filesDir, "audio_recordings")
    }
}
```

> 依赖 Task 7 在 `AudioMonitorView` 上提供 `setReportListener { ... }`。若先做本任务，临时在 `AudioMonitorView` 加空的 `fun setReportListener(l: () -> Unit) {}`，Task 7 补全。

- [ ] **Step 2: 编译确认**

Run: `./gradlew :debugtools-audiomon:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（如报 `setReportListener`/`showLastSession` 缺失，按上文先加空实现）。

- [ ] **Step 3: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/AudioMonitorModule.kt
git commit -m "feat(audiomon): inject reporter, add feedProcessedAudio and dual-stream settings"
```

---

## Task 7: AudioMonitorView —— 按钮文案、最近会话 + 上报按钮

**Files:**
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AudioMonitorView.kt`

> 先读现有 `AudioMonitorView.kt` 摸清它如何摆放 toggle 按钮与状态文本（沿用其布局风格，不引入新依赖/资源）。

- [ ] **Step 1: 读现有 View 实现**

Run: 打开 `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AudioMonitorView.kt`，确认：toggle 按钮变量名、`showMonitoringState` 如何改按钮文案、状态 `TextView` 如何加入布局。

- [ ] **Step 2: 调整按钮文案与新增上报 UI**

在 `AudioMonitorView` 中按现有风格做三处改动（保持与现有控件相同的创建/布局方式）：

1. 录制按钮文案改为录制语义——在 `showMonitoringState(recording: Boolean)` 里把按钮文字设为 `if (recording) "结束录制" else "开始录制"`（替换原 “开始/停止监控” 文案）。
2. 新增上报按钮成员与设置回调：

```kotlin
    private var reportListener: (() -> Unit)? = null
    private val reportButton: Button = Button(context).apply {
        text = "上报最近会话"
        isEnabled = false
        setOnClickListener { reportListener?.invoke() }
    }

    fun setReportListener(listener: () -> Unit) { reportListener = listener }
```

（把 `reportButton` 用与 toggle 按钮相同的方式 `addView` 进同一容器。）

3. 实现接口新方法 `showLastSession`：

```kotlin
    override fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean) {
        lastSessionText.text = "最近会话: $sessionId\n$summary" +
            if (!reporterConfigured) "\n(未配置上报接口)" else ""
        reportButton.isEnabled = reporterConfigured
    }
```

其中 `lastSessionText` 为新增的 `TextView`（与现有状态文本相同方式创建并 `addView`）：

```kotlin
    private val lastSessionText: TextView = TextView(context).apply {
        textSize = 12f
        text = ""
    }
```

> 若 Task 5/6 阶段加过 `showLastSession` / `setReportListener` 的临时空实现，这里替换为正式实现。确保 `import android.widget.Button` / `TextView` 已存在。

- [ ] **Step 3: 整模块编译 + 全量测试**

Run: `./gradlew :debugtools-audiomon:assembleDebug :debugtools-audiomon:test`
Expected: BUILD SUCCESSFUL；所有 audiomon 测试 PASS。

- [ ] **Step 4: 检查 app 集成是否仍编译**

> 现有 `app` 模块集成了 `AudioMonitorModule()`（无参构造仍兼容，因为 `reporter` 有默认值）。确认 app 仍编译；若 app 之前依赖被删除的 `save_enabled` 行为则相应调整（预期无）。

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AudioMonitorView.kt
git commit -m "feat(audiomon): record-button labels, recent-session display and report button"
```

---

## Task 8: 文档同步（README / 集成说明）

**Files:**
- Modify: `debugtools-audiomon/` 下的 README（若存在）或在 `app` 集成处补注释

- [ ] **Step 1: 检查是否有模块 README**

Run: `ls debugtools-audiomon/*.md 2>/dev/null`

- [ ] **Step 2: 补充宿主集成片段**

若有 README，追加；否则在 `app` 模块注册 `AudioMonitorModule` 处加注释，说明三步集成：

```kotlin
// 1) 实现上报接口
val reporter = object : AudioReporter {
    override fun report(session: AudioReportData) {
        // 自行上传 session.metadata / streamAWav / streamBWav ...
    }
}
// 2) 注册时注入
val audio = AudioMonitorModule(reporter = reporter)
DebugTools.builder(context).register(audio).build()
// 3) 在你的音频处理回调里推入“处理后”的 PCM16 帧
audio.feedProcessedAudio(processedFrame)
```

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "docs(audiomon): document dual-stream recording and reporting integration"
```

---

## 验收清单（实现完成后整体核对）

- [ ] `./gradlew :debugtools-audiomon:test` 全绿（含 AudioFeatures / AudioFeatureExtractor / RecordingSessionController 测试）
- [ ] `./gradlew :debugtools-audiomon:assembleDebug` 与 `:app:assembleDebug` 通过
- [ ] 一次录制在 `save_dir/<sessionId>/` 下产出 `streamB.wav` + `streamB.features.json` + `session.json`；宿主推流时另有 `streamA.*`
- [ ] `auto_report=true` 且配了 reporter 时停止录制自动回调；面板上报按钮在配了 reporter 时可用
- [ ] 未配 reporter 时上报按钮置灰且提示「未配置上报接口」
