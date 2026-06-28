# 双路滚动可视化 + 异常检测 + 时长上限 + 视觉优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 `debugtools-audiomon` 面板加双路(A/B)滚动 dB 包络 + 声谱图、四类实时异常检测(图上标注+底部列表+落盘)、录制时长上限自动结束、底部折叠异常说明,并统一视觉风格。

**Architecture:** 纯逻辑下沉:`AudioAnomalyDetector`(四规则+事件段合并)与 `AudioColors`(配色+声谱图 LUT)是纯 JVM 可测核心;滚动视图用环形缓冲(包络数组、声谱图 Bitmap 循环列)做固定窗口绘制;Presenter 把两路帧各经 `.sample(60)` 喂给检测器与视图,并用倒计时协程到时自动 `stopRecording()`。Android 耦合层(View 接口/Presenter/Module/AudioMonitorView)作为一个整体集成任务替换旧的瞬时示波器。

**Tech Stack:** Kotlin, Android(compileSdk 34, minSdk 26), kotlinx-coroutines, Android Canvas/Bitmap, 内置 org.json(测试用 `org.json:json` 已在 testImplementation)。

**参考规格:** `docs/superpowers/specs/2026-06-28-audiomon-live-viz-anomaly-design.md`

---

## File Structure

新增:
- `anomaly/StreamId.kt`, `anomaly/AnomalyType.kt`, `anomaly/AnomalyEvent.kt` — 异常模型
- `anomaly/AudioAnomalyDetector.kt` — 纯检测器(+ 测试)
- `view/AudioColors.kt` — 配色 + 声谱图 LUT(+ 测试)
- `view/ScrollingEnvelopeView.kt`, `view/SpectrogramView.kt` — 环形缓冲滚动视图
- `view/StreamLaneView.kt`, `view/AnomalyListView.kt`, `view/AnomalyLegendView.kt` — 组合视图

修改:
- `session/RecordingSessionController.kt` — `finish` 接收两路异常,写入 session.json
- `presenter/AudioView.kt` — 接口增删
- `presenter/AudioPresenter.kt` — 两路 sample、检测、倒计时、异常累积、finish 传参
- `view/AudioMonitorView.kt` — 重构为 ScrollView + 双 lane + 列表 + 折叠说明 + 新配色
- `AudioMonitorModule.kt` — 新增设置 `max_duration_sec`,传 maxDurationSec

删除:
- `view/WaveformView.kt`, `view/SpectrumView.kt`

---

## Task 1: 异常模型(StreamId / AnomalyType / AnomalyEvent)

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/anomaly/StreamId.kt`
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/anomaly/AnomalyType.kt`
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/anomaly/AnomalyEvent.kt`
- Test: `debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/anomaly/AnomalyEventTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `AnomalyEventTest.kt`:

```kotlin
package com.debugtools.audiomon.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEventTest {

    @Test
    fun `toJson carries stream type label and detail`() {
        val e = AnomalyEvent(StreamId.B, 3010L, AnomalyType.CLIPPING, "peak 0.99")
        val j = e.toJson()
        assertEquals("B", j.getString("stream"))
        assertEquals(3010L, j.getLong("timeMs"))
        assertEquals("CLIPPING", j.getString("type"))
        assertEquals("削波", j.getString("typeLabel"))
        assertEquals("peak 0.99", j.getString("detail"))
    }

    @Test
    fun `every anomaly type has a non-empty hint`() {
        assertTrue(AnomalyType.values().all { it.hint.isNotBlank() && it.label.isNotBlank() })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-audiomon:test --tests "*AnomalyEventTest*"`
Expected: FAIL(类型未定义)。

- [ ] **Step 3: 实现三个文件**

`StreamId.kt`:
```kotlin
package com.debugtools.audiomon.anomaly

/** Which of the two recorded streams an anomaly belongs to. */
enum class StreamId(val label: String) { A("A路"), B("B路") }
```

`AnomalyType.kt`:
```kotlin
package com.debugtools.audiomon.anomaly

/**
 * The kinds of audio anomalies the detector reports.
 * [hint] is the "what problem this may indicate" text reused by the collapsible
 * legend at the bottom of the panel.
 */
enum class AnomalyType(val label: String, val hint: String) {
    CLIPPING("削波", "输入增益过高或信号过强，波形被截顶产生谐波失真；常导致破音、ASR 识别率下降。"),
    SILENCE_DROPOUT("异常静音", "麦克风被占用/静音、采集中断或丢帧、VAD 误切；可能漏识别、对话中断。"),
    ENERGY_JUMP("能量突变", "突发噪声、回声、设备碰撞或 AGC 增益抖动；可能引起误唤醒、识别错误。"),
    HIGH_NOISE_FLOOR("底噪偏高", "环境噪声大、降噪/AEC 不足或硬件底噪高；降低信噪比，影响远场识别。")
}
```

`AnomalyEvent.kt`:
```kotlin
package com.debugtools.audiomon.anomaly

import org.json.JSONObject

/** One detected anomaly episode on a stream. [timeMs] is offset from recording start. */
data class AnomalyEvent(
    val stream: StreamId,
    val timeMs: Long,
    val type: AnomalyType,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stream", stream.name)
        put("timeMs", timeMs)
        put("type", type.name)
        put("typeLabel", type.label)
        put("detail", detail)
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-audiomon:test --tests "*AnomalyEventTest*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/anomaly/ \
  debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/anomaly/AnomalyEventTest.kt
git commit -m "feat(audiomon): add anomaly model (StreamId, AnomalyType, AnomalyEvent)"
```

---

## Task 2: AudioColors(配色 + 声谱图 LUT)

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AudioColors.kt`
- Test: `debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/view/AudioColorsTest.kt`

> 全部用纯 ARGB Int(不依赖 `android.graphics.Color`),故 LUT 可在 JVM 单测。

- [ ] **Step 1: 写失败测试**

创建 `AudioColorsTest.kt`:
```kotlin
package com.debugtools.audiomon.view

import com.debugtools.audiomon.anomaly.AnomalyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioColorsTest {

    @Test
    fun `spectrogram color endpoints map to first and last LUT stop`() {
        assertEquals(0xFF15151F.toInt(), AudioColors.spectrogramColor(0f))
        assertEquals(0xFFFB7185.toInt(), AudioColors.spectrogramColor(1f))
    }

    @Test
    fun `spectrogram color clamps out-of-range input`() {
        assertEquals(AudioColors.spectrogramColor(0f), AudioColors.spectrogramColor(-5f))
        assertEquals(AudioColors.spectrogramColor(1f), AudioColors.spectrogramColor(9f))
    }

    @Test
    fun `mid level is fully opaque and between stops`() {
        val c = AudioColors.spectrogramColor(0.5f)
        assertEquals(0xFF, (c ushr 24) and 0xFF) // opaque
    }

    @Test
    fun `every anomaly type has a color`() {
        assertTrue(AnomalyType.values().all { AudioColors.anomalyTypeColor(it) != 0 })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioColorsTest*"`
Expected: FAIL

- [ ] **Step 3: 实现 AudioColors**

```kotlin
package com.debugtools.audiomon.view

import com.debugtools.audiomon.anomaly.AnomalyType

/**
 * Central palette + spectrogram colormap for the audio panel. Colors are raw
 * ARGB Ints (no android.graphics.Color), so the LUT is unit-testable on the JVM.
 */
object AudioColors {
    val BG = 0xFF15151F.toInt()
    val SURFACE = 0xFF20223A.toInt()
    val TEXT = 0xFFE2E8F0.toInt()
    val TEXT_DIM = 0xFF94A3B8.toInt()
    val START = 0xFF2DD4BF.toInt()
    val STOP = 0xFFF43F5E.toInt()
    val REPORT = 0xFF3B82F6.toInt()
    val STREAM_A = 0xFFF6AD55.toInt()
    val STREAM_B = 0xFF63B3ED.toInt()
    val ANOMALY = 0xFFFB7185.toInt()

    // 5-stop spectrogram ramp: bg -> blue -> teal -> yellow -> red
    private val LUT = intArrayOf(
        0xFF15151F.toInt(), 0xFF2B4B8C.toInt(), 0xFF2DD4BF.toInt(),
        0xFFFACC15.toInt(), 0xFFFB7185.toInt()
    )

    /** Map magnitude level 0..1 to a spectrogram color (opaque). */
    fun spectrogramColor(level: Float): Int {
        val l = level.coerceIn(0f, 1f)
        val pos = l * (LUT.size - 1)
        val i = pos.toInt().coerceAtMost(LUT.size - 2)
        val f = pos - i
        val c0 = LUT[i]
        val c1 = LUT[i + 1]
        val r = lerp((c0 shr 16) and 0xFF, (c1 shr 16) and 0xFF, f)
        val g = lerp((c0 shr 8) and 0xFF, (c1 shr 8) and 0xFF, f)
        val b = lerp(c0 and 0xFF, c1 and 0xFF, f)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun anomalyTypeColor(t: AnomalyType): Int = when (t) {
        AnomalyType.CLIPPING -> 0xFFF43F5E.toInt()
        AnomalyType.SILENCE_DROPOUT -> 0xFF94A3B8.toInt()
        AnomalyType.ENERGY_JUMP -> 0xFFF6AD55.toInt()
        AnomalyType.HIGH_NOISE_FLOOR -> 0xFFA78BFA.toInt()
    }

    private fun lerp(a: Int, b: Int, f: Float): Int =
        (a + (b - a) * f).toInt().coerceIn(0, 255)
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioColorsTest*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AudioColors.kt \
  debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/view/AudioColorsTest.kt
git commit -m "feat(audiomon): add AudioColors palette and spectrogram LUT"
```

---

## Task 3: AudioAnomalyDetector(四规则 + 事件段合并)

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/anomaly/AudioAnomalyDetector.kt`
- Test: `debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/anomaly/AudioAnomalyDetectorTest.kt`

- [ ] **Step 1: 写失败测试**

创建 `AudioAnomalyDetectorTest.kt`:
```kotlin
package com.debugtools.audiomon.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioAnomalyDetectorTest {

    private fun detector() = AudioAnomalyDetector(StreamId.B, silenceThresholdDb = -50f)

    @Test
    fun `consecutive clipping frames collapse into one episode`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        // 4 clipping frames then a clean frame closes the episode
        out += d.onFrame(0, peak = 1.0f, db = -3f)
        out += d.onFrame(64, peak = 1.0f, db = -3f)
        out += d.onFrame(128, peak = 1.0f, db = -3f)
        out += d.onFrame(192, peak = 1.0f, db = -3f)
        out += d.onFrame(256, peak = 0.1f, db = -20f)
        val clips = out.filter { it.type == AnomalyType.CLIPPING }
        assertEquals(1, clips.size)
        assertEquals(0L, clips[0].timeMs)
    }

    @Test
    fun `energy jump fires once per transition not per sustained frame`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        out += d.onFrame(0, 0.1f, -40f)
        out += d.onFrame(64, 0.1f, -40f)
        out += d.onFrame(128, 0.1f, -10f)  // +30dB jump
        out += d.onFrame(192, 0.1f, -10f)  // steady, no repeat
        out += d.onFrame(256, 0.1f, -40f)  // -30dB jump
        assertEquals(2, out.count { it.type == AnomalyType.ENERGY_JUMP })
    }

    @Test
    fun `sustained true silence over 1s is a dropout`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(7) { out += d.onFrame(t, 0.0f, -70f); t += 200 } // 0..1200ms quiet, avg -70
        out += d.onFrame(t, 0.1f, -20f) // closes the quiet run
        val ev = out.filter { it.type == AnomalyType.SILENCE_DROPOUT }
        assertEquals(1, ev.size)
        assertTrue(out.none { it.type == AnomalyType.HIGH_NOISE_FLOOR })
    }

    @Test
    fun `short quiet gap under 1s is not a dropout`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(3) { out += d.onFrame(t, 0.0f, -70f); t += 200 } // 0..400ms
        out += d.onFrame(t, 0.1f, -20f)
        assertTrue(out.none { it.type == AnomalyType.SILENCE_DROPOUT })
    }

    @Test
    fun `noisy quiet segment is high noise floor not dropout`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(4) { out += d.onFrame(t, 0.1f, -55f); t += 200 } // 0..600ms, below -50 but avg -55 > -60
        out += d.onFrame(t, 0.1f, -20f)
        assertEquals(1, out.count { it.type == AnomalyType.HIGH_NOISE_FLOOR })
        assertTrue(out.none { it.type == AnomalyType.SILENCE_DROPOUT })
    }

    @Test
    fun `flush closes an open quiet segment at end of recording`() {
        val d = detector()
        val out = mutableListOf<AnomalyEvent>()
        var t = 0L
        repeat(7) { out += d.onFrame(t, 0.0f, -70f); t += 200 } // open quiet run, never closed by a loud frame
        out += d.flush(1300)
        assertEquals(1, out.count { it.type == AnomalyType.SILENCE_DROPOUT })
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioAnomalyDetectorTest*"`
Expected: FAIL

- [ ] **Step 3: 实现 AudioAnomalyDetector**

```kotlin
package com.debugtools.audiomon.anomaly

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-stream anomaly detector. Fed one display frame at a time; reports
 * episodes (coalesced) so a sustained anomaly yields a single event.
 * Pure JVM — no Android dependencies. Not thread-safe; call from one thread.
 */
class AudioAnomalyDetector(
    private val stream: StreamId,
    private val silenceThresholdDb: Float = -50f
) {
    private companion object {
        const val CLIP_THRESHOLD = 0.99f
        const val JUMP_DB = 15f
        const val NOISE_FLOOR_DB = -60f
        const val MIN_SILENCE_MS = 1000L
        const val MIN_NOISE_MS = 500L
    }

    private var prevDb: Float? = null
    private var jumpArmed = false

    private var clipOpen = false
    private var clipStart = 0L
    private var clipMaxPeak = 0f

    private var quietOpen = false
    private var quietStart = 0L
    private var quietLast = 0L
    private var quietSumDb = 0.0
    private var quietCount = 0

    fun onFrame(timeMs: Long, peak: Float, db: Float): List<AnomalyEvent> {
        val events = mutableListOf<AnomalyEvent>()

        // 1) clipping episode
        if (peak >= CLIP_THRESHOLD) {
            if (!clipOpen) { clipOpen = true; clipStart = timeMs; clipMaxPeak = peak }
            else clipMaxPeak = maxOf(clipMaxPeak, peak)
        } else if (clipOpen) {
            events += AnomalyEvent(stream, clipStart, AnomalyType.CLIPPING,
                "peak ${"%.2f".format(clipMaxPeak)}")
            clipOpen = false
        }

        // 2) energy jump (debounced — one per transition)
        prevDb?.let { pd ->
            val delta = db - pd
            if (abs(delta) > JUMP_DB) {
                if (!jumpArmed) {
                    val sign = if (delta > 0) "+" else ""
                    events += AnomalyEvent(stream, timeMs, AnomalyType.ENERGY_JUMP,
                        "$sign${delta.roundToInt()}dB")
                    jumpArmed = true
                }
            } else jumpArmed = false
        }
        prevDb = db

        // 3+4) quiet segment -> dropout or high noise floor on close
        if (db < silenceThresholdDb) {
            if (!quietOpen) { quietOpen = true; quietStart = timeMs; quietSumDb = 0.0; quietCount = 0 }
            quietSumDb += db
            quietCount++
            quietLast = timeMs
        } else if (quietOpen) {
            closeQuiet()?.let { events += it }
            quietOpen = false
        }

        return events
    }

    /** Close any open episodes at end of recording. */
    fun flush(timeMs: Long): List<AnomalyEvent> {
        val events = mutableListOf<AnomalyEvent>()
        if (clipOpen) {
            events += AnomalyEvent(stream, clipStart, AnomalyType.CLIPPING,
                "peak ${"%.2f".format(clipMaxPeak)}")
            clipOpen = false
        }
        if (quietOpen) {
            quietLast = maxOf(quietLast, timeMs)
            closeQuiet()?.let { events += it }
            quietOpen = false
        }
        return events
    }

    private fun closeQuiet(): AnomalyEvent? {
        val durMs = quietLast - quietStart
        val avg = if (quietCount > 0) quietSumDb / quietCount else NOISE_FLOOR_DB.toDouble()
        return if (avg > NOISE_FLOOR_DB) {
            if (durMs >= MIN_NOISE_MS)
                AnomalyEvent(stream, quietStart, AnomalyType.HIGH_NOISE_FLOOR, "avg ${avg.roundToInt()}dB")
            else null
        } else {
            if (durMs >= MIN_SILENCE_MS)
                AnomalyEvent(stream, quietStart, AnomalyType.SILENCE_DROPOUT, "${durMs}ms")
            else null
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `./gradlew :debugtools-audiomon:test --tests "*AudioAnomalyDetectorTest*"`
Expected: PASS(6 个测试)

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/anomaly/AudioAnomalyDetector.kt \
  debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/anomaly/AudioAnomalyDetectorTest.kt
git commit -m "feat(audiomon): add AudioAnomalyDetector with four episode-coalesced rules"
```

---

## Task 4: 异常落盘(RecordingSessionController.finish 接收异常)

**Files:**
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/session/RecordingSessionController.kt`
- Test: `debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/session/RecordingSessionControllerTest.kt`(追加用例)

- [ ] **Step 1: 追加失败测试**

在 `RecordingSessionControllerTest.kt` 顶部 import 处补充:
```kotlin
import com.debugtools.audiomon.anomaly.AnomalyEvent
import com.debugtools.audiomon.anomaly.AnomalyType
import com.debugtools.audiomon.anomaly.StreamId
```
并新增测试方法:
```kotlin
    @Test
    fun `finish writes anomalies and source into session json`() {
        val c = controller(tmp.root)
        c.start()
        repeat(4) { c.feedStreamB(sine(fftSize, 64, 0.5)) }
        val bAnoms = listOf(AnomalyEvent(StreamId.B, 300, AnomalyType.CLIPPING, "peak 1.00"))
        val report = c.finish(streamBAnomalies = bAnoms, streamAAnomalies = emptyList())!!
        val json = org.json.JSONObject(report.metadata.readText())
        assertEquals("live@~16fps", json.getString("anomalySource"))
        val bArr = json.getJSONObject("streams").getJSONObject("streamB").getJSONArray("anomalies")
        assertEquals(1, bArr.length())
        assertEquals("CLIPPING", bArr.getJSONObject(0).getString("type"))
        val aArr = json.getJSONObject("streams").getJSONObject("streamA").getJSONArray("anomalies")
        assertEquals(0, aArr.length())
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `./gradlew :debugtools-audiomon:test --tests "*RecordingSessionControllerTest*"`
Expected: FAIL(`finish` 不接受参数 / 无 `anomalySource`)。

- [ ] **Step 3: 改 finish 与 writeSessionJson**

在 `RecordingSessionController.kt` 顶部 import 增加:
```kotlin
import com.debugtools.audiomon.anomaly.AnomalyEvent
```
把 `finish()` 签名与调用改为(带默认值,保持旧测试 `finish()` 可用):
```kotlin
    @Synchronized
    fun finish(
        streamBAnomalies: List<AnomalyEvent> = emptyList(),
        streamAAnomalies: List<AnomalyEvent> = emptyList()
    ): AudioReportData? {
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
        val metadata = writeSessionJson(dir, id, endTime, bFeatures, aFeatures, aPresent,
            streamBAnomalies, streamAAnomalies)

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
```
把 `writeSessionJson` 改为接收异常并写入(替换原方法签名与 streams 构造):
```kotlin
    private fun writeSessionJson(
        dir: File,
        id: String,
        endTime: Long,
        bFeatures: AudioFeatures?,
        aFeatures: AudioFeatures?,
        aPresent: Boolean,
        bAnomalies: List<AnomalyEvent>,
        aAnomalies: List<AnomalyEvent>
    ): File {
        val streams = JSONObject().apply {
            put("streamB", streamNode(present = true, wav = "streamB.wav",
                features = "streamB.features.json", f = bFeatures, anomalies = bAnomalies))
            put("streamA", streamNode(present = aPresent,
                wav = if (aPresent) "streamA.wav" else JSONObject.NULL,
                features = if (aPresent) "streamA.features.json" else JSONObject.NULL,
                f = aFeatures, anomalies = aAnomalies))
        }
        val root = JSONObject().apply {
            put("sessionId", id)
            put("startTime", startTime)
            put("endTime", endTime)
            put("durationMs", endTime - startTime)
            put("sampleRate", sampleRate)
            put("anomalySource", "live@~16fps")
            put("streams", streams)
        }
        val file = File(dir, "session.json")
        file.writeText(root.toString())
        return file
    }
```
把 `streamNode` 增加 `anomalies` 参数:
```kotlin
    private fun streamNode(present: Boolean, wav: Any?, features: Any?, f: AudioFeatures?,
                           anomalies: List<AnomalyEvent>): JSONObject =
        JSONObject().apply {
            put("present", present)
            put("wav", wav ?: JSONObject.NULL)
            put("features", features ?: JSONObject.NULL)
            put("summary", f?.summaryJson() ?: JSONObject.NULL)
            put("anomalies", org.json.JSONArray().apply { anomalies.forEach { put(it.toJson()) } })
        }
```

- [ ] **Step 4: 运行确认通过(含旧用例)**

Run: `./gradlew :debugtools-audiomon:test --tests "*RecordingSessionControllerTest*"`
Expected: PASS(原有 5 + 新增 1）。

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/session/RecordingSessionController.kt \
  debugtools-audiomon/src/test/kotlin/com/debugtools/audiomon/session/RecordingSessionControllerTest.kt
git commit -m "feat(audiomon): persist per-stream anomalies into session.json"
```

---

## Task 5: 滚动视图(ScrollingEnvelopeView + SpectrogramView)

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/ScrollingEnvelopeView.kt`
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/SpectrogramView.kt`

> 纯 Android 自定义 View(Canvas/Bitmap),无单测;通过编译验证。环形缓冲见注释。

- [ ] **Step 1: 创建 ScrollingEnvelopeView**

```kotlin
package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Arrays

/**
 * Scrolling dB envelope over a fixed window. Holds the most recent [COLUMNS]
 * per-frame dB values in a ring buffer ([writeIndex] wraps around); onDraw
 * unrolls them oldest->newest left to right. Anomaly columns get a red marker.
 */
@SuppressLint("ViewConstructor")
class ScrollingEnvelopeView(context: Context, lineColor: Int) : View(context) {

    private companion object {
        const val COLUMNS = 160
        const val MIN_DB = -90f
    }

    private val density = resources.displayMetrics.density
    private val db = FloatArray(COLUMNS) { MIN_DB }
    private val flag = BooleanArray(COLUMNS)
    private var writeIndex = 0

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = lineColor
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = (lineColor and 0x00FFFFFF) or (0x4D shl 24) // ~30% alpha
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AudioColors.ANOMALY; strokeWidth = 1.5f * density
    }

    fun pushColumn(dbValue: Float, anomaly: Boolean) {
        db[writeIndex] = dbValue.coerceIn(MIN_DB, 0f)
        flag[writeIndex] = anomaly
        writeIndex = (writeIndex + 1) % COLUMNS
        invalidate()
    }

    /** Flag the most recently written column as anomalous (called after pushColumn). */
    fun markLast() {
        flag[(writeIndex - 1 + COLUMNS) % COLUMNS] = true
        invalidate()
    }

    fun clear() {
        Arrays.fill(db, MIN_DB); Arrays.fill(flag, false); writeIndex = 0; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val path = Path()
        for (k in 0 until COLUMNS) {
            val idx = (writeIndex + k) % COLUMNS // oldest -> newest, left -> right
            val x = w * k / (COLUMNS - 1)
            val norm = (db[idx] - MIN_DB) / (-MIN_DB) // 0..1
            val y = h - norm * h
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fillPath = Path(path).apply { lineTo(w, h); lineTo(0f, h); close() }
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, stroke)
        for (k in 0 until COLUMNS) {
            if (flag[(writeIndex + k) % COLUMNS]) {
                val x = w * k / (COLUMNS - 1)
                canvas.drawLine(x, 0f, x, h, mark)
            }
        }
    }
}
```

- [ ] **Step 2: 创建 SpectrogramView**

```kotlin
package com.debugtools.audiomon.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import java.util.Arrays

/**
 * Scrolling spectrogram. A [COLUMNS] x [BINS] bitmap is used as a ring buffer of
 * columns: each pushColumn writes one column at [writeIndex] (wraps); onDraw
 * blits the bitmap in two halves so the oldest column is leftmost. Low
 * frequencies at the bottom. Anomaly columns get a top tick.
 */
class SpectrogramView(context: Context) : View(context) {

    private companion object {
        const val COLUMNS = 160
        const val BINS = 64
    }

    private val density = resources.displayMetrics.density
    private val bitmap = Bitmap.createBitmap(COLUMNS, BINS, Bitmap.Config.ARGB_8888)
    private val column = IntArray(BINS)
    private val flag = BooleanArray(COLUMNS)
    private var writeIndex = 0
    private val paint = Paint()
    private val mark = Paint().apply { color = AudioColors.ANOMALY; strokeWidth = 2f * density }

    init { bitmap.eraseColor(AudioColors.BG) }

    /** @param magnitudes normalized 0..1, length fftSize/2; empty => skip. */
    fun pushColumn(magnitudes: FloatArray, anomaly: Boolean) {
        if (magnitudes.isEmpty()) return
        val per = maxOf(1, magnitudes.size / BINS)
        for (b in 0 until BINS) {
            val start = b * per
            val end = if (b == BINS - 1) magnitudes.size else minOf(magnitudes.size, (b + 1) * per)
            var sum = 0f; var cnt = 0
            var i = start
            while (i < end) { sum += magnitudes[i]; cnt++; i++ }
            val level = if (cnt > 0) sum / cnt else 0f
            column[BINS - 1 - b] = AudioColors.spectrogramColor(level) // low freq at bottom
        }
        bitmap.setPixels(column, 0, 1, writeIndex, 0, 1, BINS)
        flag[writeIndex] = anomaly
        writeIndex = (writeIndex + 1) % COLUMNS
        invalidate()
    }

    fun markLast() {
        flag[(writeIndex - 1 + COLUMNS) % COLUMNS] = true
        invalidate()
    }

    fun clear() {
        bitmap.eraseColor(AudioColors.BG); Arrays.fill(flag, false); writeIndex = 0; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val leftCount = COLUMNS - writeIndex // columns [writeIndex, COLUMNS) are oldest
        val split = (w.toFloat() * leftCount / COLUMNS).toInt()
        if (leftCount > 0) {
            canvas.drawBitmap(bitmap, Rect(writeIndex, 0, COLUMNS, BINS), Rect(0, 0, split, h), paint)
        }
        if (writeIndex > 0) {
            canvas.drawBitmap(bitmap, Rect(0, 0, writeIndex, BINS), Rect(split, 0, w, h), paint)
        }
        for (k in 0 until COLUMNS) {
            if (flag[(writeIndex + k) % COLUMNS]) {
                val x = w.toFloat() * k / COLUMNS
                canvas.drawLine(x, 0f, x, 4f * density, mark)
            }
        }
    }
}
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :debugtools-audiomon:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/ScrollingEnvelopeView.kt \
  debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/SpectrogramView.kt
git commit -m "feat(audiomon): add ring-buffer scrolling envelope and spectrogram views"
```

---

## Task 6: 组合视图(StreamLaneView + AnomalyListView + AnomalyLegendView)

**Files:**
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/StreamLaneView.kt`
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AnomalyListView.kt`
- Create: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AnomalyLegendView.kt`

- [ ] **Step 1: 创建 StreamLaneView**

```kotlin
package com.debugtools.audiomon.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.audiomon.anomaly.StreamId

/** One stream's lane: chip label + scrolling envelope + spectrogram, in a card. */
@SuppressLint("ViewConstructor")
class StreamLaneView(context: Context, stream: StreamId) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val accent = if (stream == StreamId.A) AudioColors.STREAM_A else AudioColors.STREAM_B
    private val envelope = ScrollingEnvelopeView(context, accent)
    private val spectro = SpectrogramView(context)

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(AudioColors.SURFACE)
            cornerRadius = 12f * density
            setStroke((1.5f * density).toInt(), accent)
        }
        val pad = (10 * density).toInt()
        setPadding(pad, (8 * density).toInt(), pad, (8 * density).toInt())

        val subtitle = if (stream == StreamId.A) "A路 · 处理后" else "B路 · 麦克风"
        addView(chip(subtitle), LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(label("能量包络"))
        addView(envelope, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
        addView(label("声谱图"))
        addView(spectro, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = (2 * density).toInt() })
    }

    fun pushFrame(db: Float, spectrum: FloatArray) {
        envelope.pushColumn(db, false)
        if (spectrum.isNotEmpty()) spectro.pushColumn(spectrum, false)
    }

    fun markLastAnomaly() {
        envelope.markLast(); spectro.markLast()
    }

    fun clear() { envelope.clear(); spectro.clear() }

    private fun chip(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(accent)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        val h = (3 * density).toInt(); val w = (8 * density).toInt()
        setPadding(w, h, w, h)
        background = GradientDrawable().apply {
            cornerRadius = 20f * density
            setStroke((1 * density).toInt(), accent)
        }
    }

    private fun label(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(AudioColors.TEXT_DIM)
        textSize = 10f
        setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
    }
}
```

- [ ] **Step 2: 创建 AnomalyListView**

```kotlin
package com.debugtools.audiomon.view

import android.content.Context
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

/** Accumulating anomaly log (newest on top), capped to [MAX] rows. */
class AnomalyListView(context: Context) : LinearLayout(context) {

    private companion object { const val MAX = 50 }

    private val density = resources.displayMetrics.density

    init { orientation = VERTICAL }

    /** @param message preformatted "m:ss · [B] 削波 · peak 0.99"; dotColor per anomaly type. */
    fun addEntry(message: String, dotColor: Int) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
        }
        row.addView(TextView(context).apply { text = "● "; setTextColor(dotColor); textSize = 11f })
        row.addView(TextView(context).apply {
            text = message; setTextColor(AudioColors.TEXT); textSize = 11f; typeface = Typeface.MONOSPACE
        })
        addView(row, 0) // newest on top
        if (childCount > MAX) removeViewAt(childCount - 1)
    }

    fun clear() { removeAllViews() }
}
```

- [ ] **Step 3: 创建 AnomalyLegendView**

```kotlin
package com.debugtools.audiomon.view

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.audiomon.anomaly.AnomalyType

/** Bottom collapsible legend: tap header to expand the four anomaly explanations. */
class AnomalyLegendView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val header: TextView
    private val body: LinearLayout
    private var expanded = false

    init {
        orientation = VERTICAL
        header = TextView(context).apply {
            text = "▸ 异常类型说明"
            setTextColor(AudioColors.TEXT_DIM)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (10 * density).toInt(), 0, (6 * density).toInt())
            setOnClickListener { toggle() }
        }
        body = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = View.GONE
        }
        for (t in AnomalyType.values()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            }
            row.addView(TextView(context).apply {
                text = "● "; setTextColor(AudioColors.anomalyTypeColor(t)); textSize = 12f
            })
            row.addView(TextView(context).apply {
                text = "${t.label}: ${t.hint}"; setTextColor(AudioColors.TEXT_DIM); textSize = 11f
            })
            body.addView(row)
        }
        addView(header)
        addView(body)
    }

    private fun toggle() {
        expanded = !expanded
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        header.text = (if (expanded) "▾" else "▸") + " 异常类型说明"
    }
}
```

- [ ] **Step 4: 编译确认**

Run: `./gradlew :debugtools-audiomon:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/StreamLaneView.kt \
  debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AnomalyListView.kt \
  debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AnomalyLegendView.kt
git commit -m "feat(audiomon): add stream lane, anomaly list, and collapsible legend views"
```

---

## Task 7: 集成层(AudioView + Presenter + Module + AudioMonitorView + 删旧视图 + 时长上限)

> 这些文件互相依赖、必须整体编译。包含删除 `WaveformView`/`SpectrumView`。完成后构建 app。

**Files:**
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/presenter/AudioView.kt`
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/presenter/AudioPresenter.kt`
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/AudioMonitorModule.kt`
- Modify: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/AudioMonitorView.kt`
- Delete: `debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/WaveformView.kt`, `SpectrumView.kt`

- [ ] **Step 1: 改 AudioView 接口**

整体替换 `AudioView.kt`:
```kotlin
package com.debugtools.audiomon.presenter

import com.debugtools.audiomon.anomaly.AnomalyEvent
import com.debugtools.audiomon.anomaly.StreamId

/** MVP View interface for the audio monitor module. */
interface AudioView {
    /** Update the status text line (also used for the recording countdown). */
    fun showStatus(text: String)

    /** Update the record button appearance. */
    fun showMonitoringState(isRecording: Boolean)

    /** Register callback for the start/stop record button. */
    fun setToggleListener(listener: () -> Unit)

    /** Register callback for the report-last-session button. */
    fun setReportListener(listener: () -> Unit)

    /** Show the most recent finished session; reporterConfigured gates the upload button. */
    fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean)

    /** Reset both lanes and the anomaly list at the start of a recording. */
    fun clearLive()

    /** Append one display frame to a stream's lane (envelope uses db, spectrogram uses spectrum). */
    fun pushLiveFrame(stream: StreamId, db: Float, spectrum: FloatArray)

    /** Mark the latest column of the stream's lane anomalous + append to the anomaly list. */
    fun showAnomaly(event: AnomalyEvent)
}
```

- [ ] **Step 2: 删除旧视图**

```bash
git rm debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/WaveformView.kt \
  debugtools-audiomon/src/main/kotlin/com/debugtools/audiomon/view/SpectrumView.kt
```

- [ ] **Step 3: 重写 AudioMonitorView**

整体替换 `AudioMonitorView.kt`:
```kotlin
package com.debugtools.audiomon.view

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
import com.debugtools.audiomon.anomaly.AnomalyEvent
import com.debugtools.audiomon.anomaly.StreamId
import com.debugtools.audiomon.presenter.AudioView

/**
 * Scrollable audio panel: record controls, two stream lanes (A/B) with scrolling
 * envelope + spectrogram, an accumulating anomaly list, and a collapsible legend.
 */
@SuppressLint("ViewConstructor")
class AudioMonitorView(context: Context) : ScrollView(context), AudioView {

    private val density = resources.displayMetrics.density
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val laneA = StreamLaneView(context, StreamId.A)
    private val laneB = StreamLaneView(context, StreamId.B)
    private val anomalyList = AnomalyListView(context)
    private val legend = AnomalyLegendView(context)
    private val statusText = TextView(context)
    private val lastSessionText = TextView(context)
    private val toggleBtn = TextView(context)
    private val reportBtn = TextView(context)
    private var toggleListener: (() -> Unit)? = null
    private var reportListener: (() -> Unit)? = null

    private fun mx(v: Float) = (v * density).toInt()

    init {
        setBackgroundColor(AudioColors.BG)

        toggleBtn.apply {
            text = "▶ 开始录制"; setTextColor(AudioColors.TEXT); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, mx(10f), 0, mx(10f))
            background = pill(AudioColors.START)
            setOnClickListener { toggleListener?.invoke() }
        }
        statusText.apply { setTextColor(AudioColors.TEXT_DIM); textSize = 12f; setPadding(mx(2f), mx(8f), mx(2f), mx(8f)) }
        lastSessionText.apply { setTextColor(AudioColors.TEXT_DIM); textSize = 12f }
        reportBtn.apply {
            text = "📤 上报最近会话"; setTextColor(AudioColors.TEXT); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            alpha = 0.4f; isClickable = false; isFocusable = false
            setPadding(0, mx(10f), 0, mx(10f))
            background = pill(AudioColors.REPORT)
            setOnClickListener { reportListener?.invoke() }
        }

        content.setPadding(mx(12f), mx(12f), mx(12f), mx(12f))
        content.addView(toggleBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(laneA, laneParams())
        content.addView(laneB, laneParams())
        content.addView(sectionLabel("⚠ 异常"))
        content.addView(anomalyList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(lastSessionText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = mx(8f) })
        content.addView(reportBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = mx(4f) })
        content.addView(legend, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        addView(content)
    }

    private fun laneParams() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = mx(10f) }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = 10f * density; setColor(color)
    }

    private fun sectionLabel(text: String) = TextView(context).apply {
        this.text = text; setTextColor(AudioColors.TEXT_DIM); textSize = 11f
        typeface = Typeface.DEFAULT_BOLD; setPadding(0, mx(12f), 0, mx(4f))
    }

    private fun fmtTime(ms: Long): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    // --- AudioView ---

    override fun showStatus(text: String) { statusText.text = text }

    override fun showMonitoringState(isRecording: Boolean) {
        toggleBtn.text = if (isRecording) "⏹ 结束录制" else "▶ 开始录制"
        toggleBtn.background = pill(if (isRecording) AudioColors.STOP else AudioColors.START)
    }

    override fun setToggleListener(listener: () -> Unit) { toggleListener = listener }
    override fun setReportListener(listener: () -> Unit) { reportListener = listener }

    override fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean) {
        lastSessionText.text = "最近会话: $sessionId\n$summary" +
            if (!reporterConfigured) "\n(未配置上报接口)" else ""
        reportBtn.alpha = if (reporterConfigured) 1f else 0.4f
        reportBtn.isClickable = reporterConfigured
        reportBtn.isFocusable = reporterConfigured
    }

    override fun clearLive() { laneA.clear(); laneB.clear(); anomalyList.clear() }

    override fun pushLiveFrame(stream: StreamId, db: Float, spectrum: FloatArray) {
        (if (stream == StreamId.A) laneA else laneB).pushFrame(db, spectrum)
    }

    override fun showAnomaly(event: AnomalyEvent) {
        (if (event.stream == StreamId.A) laneA else laneB).markLastAnomaly()
        anomalyList.addEntry(
            "${fmtTime(event.timeMs)} · [${event.stream.label}] ${event.type.label} · ${event.detail}",
            AudioColors.anomalyTypeColor(event.type)
        )
    }
}
```

- [ ] **Step 4: 重写 AudioPresenter**

整体替换 `AudioPresenter.kt`:
```kotlin
package com.debugtools.audiomon.presenter

import android.util.Log
import com.debugtools.audiomon.anomaly.AnomalyEvent
import com.debugtools.audiomon.anomaly.AudioAnomalyDetector
import com.debugtools.audiomon.anomaly.StreamId
import com.debugtools.audiomon.audio.AudioRecorderWrapper
import com.debugtools.audiomon.audio.FftProcessor
import com.debugtools.audiomon.report.AudioReportData
import com.debugtools.audiomon.report.AudioReporter
import com.debugtools.audiomon.session.RecordingSessionController
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.sample
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

/**
 * MVP Presenter for dual-stream recording with live scrolling visualization,
 * anomaly detection, and a max-duration auto-stop.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AudioPresenter(
    private val sampleRate: Int = AudioRecorderWrapper.DEFAULT_SAMPLE_RATE,
    private val fftSize: Int = AudioRecorderWrapper.DEFAULT_FFT_SIZE,
    private val rootDir: File,
    private val silenceThresholdDb: Float = -50f,
    private val autoReport: Boolean = false,
    private val maxDurationSec: Int = 10,
    private val reporter: AudioReporter? = null
) {
    private companion object {
        const val TAG = "AudioPresenter"
        const val MIN_DB = -90f
    }

    private var view: AudioView? = null
    private var recorder: AudioRecorderWrapper? = null
    @Volatile private var controller: RecordingSessionController? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val reportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val aFrameFlow = MutableSharedFlow<ShortArray>(0, 8, BufferOverflow.DROP_OLDEST)

    private var recordJob: Job? = null
    private var bUiJob: Job? = null
    private var aUiJob: Job? = null
    private var durationJob: Job? = null
    private var isRecording = false
    private var lastSession: AudioReportData? = null
    private var startTimeMs = 0L

    private var bDetector = AudioAnomalyDetector(StreamId.B, silenceThresholdDb)
    private var aDetector = AudioAnomalyDetector(StreamId.A, silenceThresholdDb)
    private val bAnomalies = mutableListOf<AnomalyEvent>()
    private val aAnomalies = mutableListOf<AnomalyEvent>()

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
        aFrameFlow.tryEmit(frame)
    }

    fun startRecording() {
        val v = view ?: return
        if (recordJob?.isActive == true) return

        val ctrl = RecordingSessionController(rootDir, sampleRate, fftSize, silenceThresholdDb)
        ctrl.start()
        controller = ctrl

        val rec = AudioRecorderWrapper(sampleRate, fftSize)
        recorder = rec
        val result = rec.start()
        if (result.isFailure) {
            v.showStatus("❌ ${result.exceptionOrNull()?.message}")
            ctrl.finish(); controller = null; recorder = null
            return
        }

        // reset live state
        bDetector = AudioAnomalyDetector(StreamId.B, silenceThresholdDb)
        aDetector = AudioAnomalyDetector(StreamId.A, silenceThresholdDb)
        bAnomalies.clear(); aAnomalies.clear()
        startTimeMs = System.currentTimeMillis()
        isRecording = true
        v.clearLive()
        v.showMonitoringState(true)

        // faithful WAV: every frame on IO
        recordJob = scope.launch(Dispatchers.IO) {
            try {
                rec.audioStream.collect { controller?.feedStreamB(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "stream B recording loop failed", e)
                withContext(Dispatchers.Main) { view?.showStatus("❌ 录制中断: ${e.message}") }
            }
        }

        bUiJob = scope.launch {
            rec.audioStream.sample(60).collect { processLiveFrame(StreamId.B, it, bDetector, bAnomalies) }
        }
        aUiJob = scope.launch {
            aFrameFlow.sample(60).collect { processLiveFrame(StreamId.A, it, aDetector, aAnomalies) }
        }

        durationJob = scope.launch {
            for (sec in 0 until maxDurationSec) {
                withContext(Dispatchers.Main) {
                    view?.showStatus("🎙️ 录制中 ${fmt(sec)} / ${fmt(maxDurationSec)}\n会话: ${ctrl.currentSessionDir?.name}")
                }
                delay(1000)
            }
            withContext(Dispatchers.Main) { if (isRecording) stopRecording() }
        }
    }

    private suspend fun processLiveFrame(
        stream: StreamId, frame: ShortArray,
        detector: AudioAnomalyDetector, sink: MutableList<AnomalyEvent>
    ) {
        val peak = peakOf(frame)
        val db = ampToDb(FftProcessor.computeRms(frame))
        val spectrum = if (frame.size == fftSize) FftProcessor.computeMagnitudes(frame, fftSize) else FloatArray(0)
        val timeMs = System.currentTimeMillis() - startTimeMs
        val events = detector.onFrame(timeMs, peak, db)
        withContext(Dispatchers.Main) {
            view?.pushLiveFrame(stream, db, spectrum)
            for (e in events) view?.showAnomaly(e)
        }
        if (events.isNotEmpty()) sink.addAll(events)
    }

    fun stopRecording() {
        durationJob?.cancel(); durationJob = null
        recordJob?.cancel(); bUiJob?.cancel(); aUiJob?.cancel()
        recordJob = null; bUiJob = null; aUiJob = null

        recorder?.stop(); recorder?.destroy(); recorder = null

        if (isRecording) {
            val elapsed = System.currentTimeMillis() - startTimeMs
            bAnomalies.addAll(bDetector.flush(elapsed))
            aAnomalies.addAll(aDetector.flush(elapsed))
        }

        val report = controller?.finish(bAnomalies.toList(), aAnomalies.toList())
        controller = null
        isRecording = false
        lastSession = report
        view?.showMonitoringState(false)

        if (report != null) {
            val aState = if (report.streamAWav != null) "A路+B路" else "仅B路"
            val sizeKb = (report.streamBWav?.length() ?: 0L) / 1024
            val anomCount = bAnomalies.size + aAnomalies.size
            view?.showStatus("✅ 会话完成: ${report.sessionId}")
            view?.showLastSession(report.sessionId, "$aState | B路 ${sizeKb}KB | 异常 $anomCount", reporter != null)
            if (autoReport && reporter != null) dispatchReport(report)
        } else {
            view?.showStatus("已停止")
        }
    }

    fun reportLastSession() {
        val report = lastSession ?: return
        if (reporter == null) { view?.showStatus("⚠️ 未配置上报接口"); return }
        dispatchReport(report)
    }

    private fun dispatchReport(report: AudioReportData) {
        reportScope.launch {
            val ok = runCatching { reporter?.report(report) }.isSuccess
            withContext(Dispatchers.Main) {
                view?.showStatus(if (ok) "📤 已上报: ${report.sessionId}" else "❌ 上报失败: ${report.sessionId}")
            }
        }
    }

    fun toggleMonitoring() { if (isRecording) stopRecording() else startRecording() }

    private fun peakOf(frame: ShortArray): Float {
        var m = 0
        for (s in frame) { val a = if (s.toInt() == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else abs(s.toInt()); if (a > m) m = a }
        return m.toFloat() / Short.MAX_VALUE
    }

    private fun ampToDb(amp: Float): Float =
        if (amp <= 1e-7f) MIN_DB else (20f * log10(amp)).coerceAtLeast(MIN_DB)

    private fun fmt(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
}
```

- [ ] **Step 5: 改 AudioMonitorModule(新增 max_duration_sec 设置 + 传参)**

在 `AudioMonitorModule.onAttach` 读取设置处,新增读取并传入 presenter。把 `onAttach` 内构造 presenter 的部分改为:
```kotlin
        val autoReport = storage.getBoolean("auto_report", false)
        val silenceThresholdDb = storage.getString("silence_threshold_db", "-50").toFloatOrNull() ?: -50f
        val maxDurationSec = storage.getString("max_duration_sec", "10").toIntOrNull() ?: 10

        val rootDir = File(rootPath).also { if (!it.exists()) it.mkdirs() }

        presenter = AudioPresenter(
            sampleRate = sampleRate,
            rootDir = rootDir,
            silenceThresholdDb = silenceThresholdDb,
            autoReport = autoReport,
            maxDurationSec = maxDurationSec,
            reporter = reporter
        )
```
在 `buildSettings()` 的 `items` 列表里,`auto_report` 之后(或之前)新增一项:
```kotlin
                    SettingItem.SingleSelect(
                        key = "max_duration_sec",
                        label = "录制时长上限",
                        options = listOf("10", "20", "30", "40", "50", "60"),
                        default = "10",
                        description = "到达时长自动结束录制并落盘（秒）"
                    ),
```

- [ ] **Step 6: 整模块 + app 编译,全量测试**

Run:
```bash
./gradlew :debugtools-audiomon:assembleDebug :debugtools-audiomon:test :app:assembleDebug
```
Expected: BUILD SUCCESSFUL；所有单测通过(anomaly/colors/detector/controller/features/fft/wav)。
> 若 `:app` 因旧 `showWaveform/showSpectrum` 引用失败,检查 app 未直接用这些(预期不用)。

- [ ] **Step 7: 提交**

```bash
git add -A debugtools-audiomon
git commit -m "feat(audiomon): wire dual-stream scrolling viz, anomaly annotation, duration cap, restyle panel"
```

---

## Task 8: 文档更新(README 同步新行为)

**Files:**
- Modify: `debugtools-audiomon/README.md`

- [ ] **Step 1: 在 README「能力概览」补充**

在 README 的能力列表中追加三条:
```markdown
- **双路滚动可视化**：A/B 两路各「dB 能量包络 + 声谱图」，最近 ~10s 窗口实时滚动。
- **实时异常检测**：削波 / 异常静音 / 能量突变 / 底噪偏高，图上标注 + 底部列表，并写入 `session.json`（`anomalySource: live@~16fps`）。底部「异常类型说明」可折叠查看每类问题含义。
- **录制时长上限**：设置 `max_duration_sec`（10/20/30/40/50/60 秒），到时自动结束并落盘。
```
并在「设置项」表格追加一行:
```markdown
| `max_duration_sec` | 录制时长上限（秒，到时自动结束） | `10` |
```

- [ ] **Step 2: 提交**

```bash
git add debugtools-audiomon/README.md
git commit -m "docs(audiomon): document scrolling viz, anomaly detection, duration cap"
```

---

## 验收清单（实现完成后整体核对）

- [ ] `./gradlew :debugtools-audiomon:test` 全绿(新增 AnomalyEventTest / AudioColorsTest / AudioAnomalyDetectorTest + 扩充的 RecordingSessionControllerTest）
- [ ] `:debugtools-audiomon:assembleDebug` 与 `:app:assembleDebug` 通过
- [ ] 面板:两路 lane(包络+声谱)实时滚动;异常在图上标红 + 底部列表(时间+类型+detail)
- [ ] `session.json` 含 `anomalySource` 与每路 `anomalies` 数组
- [ ] 时长上限到点自动结束落盘;状态栏倒计时 `m:ss / m:ss`
- [ ] 底部「异常类型说明」默认折叠,点击展开四类
- [ ] 视觉:新配色生效;旧 `WaveformView`/`SpectrumView` 已删除
