# 设计:双路滚动波形/声谱 + 实时异常检测落盘 + 录制时长上限 + 视觉优化

日期：2026-06-28
模块：`debugtools-audiomon`
状态：已批准设计要点，待写实现计划
前置：`docs/superpowers/specs/2026-06-28-audiomon-dual-stream-design.md`（双路录制基础）

## 1. 背景与目标

现有音频面板只显示**当前瞬间**的波形与频谱（实时示波器），无法看整段录音的变化趋势；A 路（App 注入的处理后音频）完全不可视化；没有异常提示；样式偏简陋。

本次为「音频监控」增加四件事：

1. **双路滚动可视化**：A/B 两路各一个 lane，每 lane = 「dB 能量包络」+「声谱图」，最近 ~10s 窗口实时向左滚动。
2. **实时异常检测 + 标注 + 落盘**：四类异常（削波/异常静音/能量突变/底噪偏高），图上标注 + 下方文字列表（时间点+问题），并写入会话目录。
3. **录制时长上限**：可选 10（默认）/20/30/40/50/60 秒，到时自动结束并落盘。
4. **视觉优化**：统一配色、按钮、图表与 lane 卡片样式（仅 audiomon 面板）。

## 2. 范围

**做：** 上述 4 项，限 `debugtools-audiomon` 模块。
**不做（YAGNI / 已确认）：**
- 不做两路对比/对齐（设计阶段已永久去掉）。
- 不动 DebugTools 外层 chrome（顶栏 Tab、悬浮按钮、最小化/简要按钮）。
- 异常检测跑在 ~16fps 显示帧上（已确认），不对 WAV 做离线重分析；落盘的异常标注 `source: "live@~16fps"`。
- 滚动窗口长度、各检测阈值先用常量，暂不做设置项（仅时长上限做设置项）。

## 3. 滚动可视化

### 3.1 两路 lane
A 路（处理后）、B 路（麦克风）各一个 `StreamLaneView`，每个含：
- 顶部 lane 标签（`A路 · 处理后` / `B路 · 麦克风`，带该路主题色）。
- `ScrollingEnvelopeView`：dB 能量包络，最近窗口向左滚。
- `SpectrogramView`：频率×时间热力图，向左滚。

窗口长度 `WINDOW_SECONDS = 10`；列数按显示帧率推算（~16fps → ~160 列，取常量 `COLUMNS = 160`）。

### 3.2 环形缓冲（性能关键）
两个滚动视图都用**环形缓冲**存"最近 COLUMNS 列"：
- `ScrollingEnvelopeView`：`FloatArray(COLUMNS)` 存 dB + `BooleanArray(COLUMNS)` 存异常标记；写指针绕回；`onDraw` 按写指针把环形序列展开成左→右时间轴。
- `SpectrogramView`：维护一张 `Bitmap`（宽=COLUMNS，高=频段数），用**环形列写入下标**：每来一列只 `setPixels` 写 1 列，`onDraw` 用两段 `drawBitmap`（写指针右侧的旧列在前、左侧的新列在后）拼出滚动效果——避免每帧整图左移的 O(宽×高) 开销。

> 环形下标绕回逻辑加注释说明。

### 3.3 声谱图色带
不用灰阶。用多停靠点 LUT（深→蓝→青→黄→红），幅度 0..1 映射到颜色（见 §7 配色）。

## 4. 异常检测（纯逻辑，可测）

新增 `AudioAnomalyDetector`，**每路一个实例**，按显示帧喂入。只需振幅信息，不需频谱：

```kotlin
class AudioAnomalyDetector(
    private val stream: StreamId,
    private val silenceThresholdDb: Float = -50f,   // 复用设置 silence_threshold_db
)
fun onFrame(timeMs: Long, peak: Float, db: Float): List<AnomalyEvent>  // 返回本帧"关闭"的事件
fun flush(timeMs: Long): List<AnomalyEvent>                            // 停止录制时关闭未闭合的段
```

### 4.1 四条规则（事件段合并）

| 类型 (AnomalyType) | 判定 | 事件 detail |
|---|---|---|
| `CLIPPING` 削波/过载 | `peak ≥ 0.99` | 持续帧合并为一段，记 `max peak` 与时长 |
| `ENERGY_JUMP` 能量突变 | `\|db − prevDb\| > 15dB` | 仅在跳变那一帧出一条；同方向持续不重复出，回稳后可再触发 |
| `SILENCE_DROPOUT` 异常静音/断流 | `db < silenceThresholdDb` 连续 ≥ `1000ms`，且该段平均 `db ≤ −60dB`（真静音） | 段结束（或录制结束 flush）时出一条，记时长 |
| `HIGH_NOISE_FLOOR` 底噪偏高 | `db < silenceThresholdDb` 的安静段，但平均 `db > −60dB`（静而不净），持续 ≥ `500ms` | 段结束时出一条，记平均 dB |

> `SILENCE_DROPOUT` 与 `HIGH_NOISE_FLOOR` 互斥：同一安静段按平均 dB 是否 ≤ −60 二选一。
> 默认常量：`CLIP_THRESHOLD=0.99f`、`JUMP_DB=15f`、`NOISE_FLOOR_DB=-60f`、`MIN_SILENCE_MS=1000`、`MIN_NOISE_MS=500`。`silenceThresholdDb` 来自设置。

### 4.2 事件结构
```kotlin
enum class StreamId(val label: String) { A("A路"), B("B路") }
// hint = 该异常"可能引起的问题"，供底部折叠说明区复用（见 §7.3）
enum class AnomalyType(val label: String, val hint: String) {
    CLIPPING("削波", "输入增益过高或信号过强，波形被截顶产生谐波失真；常导致破音、ASR 识别率下降。"),
    SILENCE_DROPOUT("异常静音", "麦克风被占用/静音、采集中断或丢帧、VAD 误切；可能漏识别、对话中断。"),
    ENERGY_JUMP("能量突变", "突发噪声、回声、设备碰撞或 AGC 增益抖动；可能引起误唤醒、识别错误。"),
    HIGH_NOISE_FLOOR("底噪偏高", "环境噪声大、降噪/AEC 不足或硬件底噪高；降低信噪比，影响远场识别。")
}
data class AnomalyEvent(val stream: StreamId, val timeMs: Long, val type: AnomalyType, val detail: String) {
    fun toJson(): JSONObject  // {stream, timeMs, type, typeLabel, detail}
}
```
`timeMs` = 距开始录制的流逝时间（`System.currentTimeMillis() − startTime`），在显示帧上取值。

## 5. 异常落盘

检测到的异常按路写入 `session.json`：

```json
"streams": {
  "streamB": { "...": "...", "anomalies": [
    { "timeMs": 3010, "type": "CLIPPING", "typeLabel": "削波", "detail": "peak 0.99" }
  ]},
  "streamA": { "...": "...", "anomalies": [ ... ] }
},
"anomalySource": "live@~16fps"
```

实现：Presenter 录制期累积两路异常列表，`stopRecording` 时调用
`controller.finish(anomaliesB, anomaliesA)`（新增带默认值参数，旧测试 `finish()` 不受影响）。`RecordingSessionController.streamNode` 增加 `anomalies` 数组；root 增加 `anomalySource`。异常已嵌入 `session.json`，故 `AudioReportData` 不新增字段（随 `metadata` 一起上报）。

## 6. 录制时长上限

新增设置 `max_duration_sec`（SingleSelect，**必须有上限**）：

| 选项 | 值 |
|---|---|
| **10 秒（默认）** | 10 |
| 20 / 30 / 40 / 50 秒 | 20/30/40/50 |
| 1 分钟 | 60 |

实现：`AudioPresenter` 增构造参 `maxDurationSec: Int = 10`。`startRecording` 启动**倒计时协程**：每秒刷新状态 `🎙️ 录制中 0:07 / 0:10`，到点在 Main 线程调用 `stopRecording()`（与手动停止同一条路径，产物完全一致）。计时协程存为 `durationJob`，在 `stopRecording`/`detach` 取消。

## 7. 视觉优化（仅 audiomon 面板）

### 7.1 配色（深色）
| 用途 | 色值 |
|---|---|
| 背景 bg | `#15151F` |
| 卡片/surface | `#20223A` |
| 主文字 / 次文字 | `#E2E8F0` / `#94A3B8` |
| 录制(开始) 主按钮 | `#2DD4BF`（teal）|
| 录制中(结束) | `#F43F5E`（red）|
| 上报 次按钮 | `#3B82F6`（blue）|
| A 路主题色 | `#F6AD55`（amber）|
| B 路主题色 | `#63B3ED`（cyan）|
| 异常标记/告警 | `#FB7185` |
| 声谱图色带 LUT | `#15151F → #2B4B8C → #2DD4BF → #FACC15 → #FB7185`（5 停靠点，幅度 0..1 线性插值）|

### 7.2 组件样式
- **按钮**：圆角 10dp，统一内边距，主按钮填充主题色 + 白字加粗；禁用态 `alpha 0.4`。
- **lane 卡片**：圆角 12dp 的 surface 卡，左侧 3dp 该路主题色竖条；lane 标签做成小 chip（圆角胶囊，主题色描边）。
- **区块标签**：11sp、字间距、次文字色。
- **状态栏 / 倒计时**：12sp，录制中主题色高亮。
- **异常列表**：每条 `0:03 · [B] 削波 · peak 0.99`，时间用等宽字体对齐；类型用小色点（按类型着色）；最多保留最近 50 条（环形丢弃旧条目）。
- **包络**：lane 主题色描边 + 同色 30% 透明渐变填充；异常列用 `#FB7185` 竖线标注。
- 统一 12–16dp 间距；面板整体放进 `ScrollView`（4 图 + 列表超出面板高度）。

### 7.3 底部「异常类型说明」折叠区
面板**最下方**一个折叠区，默认收起，点击展开，列出四类异常**可能引起的问题**：
- 折叠头一行：`▸ 异常类型说明`（点击切换；箭头 `▸`/`▾`，次文字色）。
- 展开体：四条，每条 = 该类型的小色点 + `label` + `hint`（文案来自 `AnomalyType.hint`，§4.2），自动覆盖全部 `AnomalyType`，新增类型无需改 UI。
- 默认 `collapsed = true`；纯前端，无 Presenter 参与。

## 8. 数据流（Presenter 编排）

- **两路对称化**：新增 `aFrameFlow: MutableSharedFlow<ShortArray>(replay=0, extraBufferCapacity=8, DROP_OLDEST)`。`feedProcessedAudio(frame)` 同时 `controller?.feedStreamA(frame)`（落盘）+ `aFrameFlow.tryEmit(frame)`（可视化）。
- **两路各一个 `.sample(60)` 协程**（~16fps）：算 `peak / rms→db / spectrum` → `view.pushLiveFrame(stream, db, spectrum)`（Main）→ `detector.onFrame(timeMs, peak, db)` → 对每个事件 `view.markAnomaly(stream, type)` + `view.addAnomalyEntry(...)` + 累积到该路 anomaly 列表。
  - B 路源自 `rec.audioStream`；A 路源自 `aFrameFlow`。
  - 录制落盘（streamB）仍由独立 `recordJob`（`Dispatchers.IO`，每帧）负责，与可视化解耦（保真，见前置 spec 的修复）。
- `startRecording`：`view.clearLive()` + 重置两个 detector + 启动 `durationJob`。
- `stopRecording`：取消 `durationJob` 与两个 sample 协程；对两个 detector 调 `flush(elapsed)` 收尾事件；`controller.finish(anomaliesB, anomaliesA)` 落盘。

## 9. AudioView 接口变化

移除 `showWaveform` / `showSpectrum`（瞬时示波器被滚动 lane 取代）。新增：
```kotlin
fun clearLive()
fun pushLiveFrame(stream: StreamId, db: Float, spectrum: FloatArray)
fun markAnomaly(stream: StreamId, type: AnomalyType)
fun addAnomalyEntry(text: String)
```
保留 `showStatus`（含倒计时）、`showMonitoringState`、`setToggleListener`、`setReportListener`、`showLastSession`。`markAnomaly` 标注"该路最近一列"（事件段在段末上报，标注末列即可，调试足够）。

## 10. 组件 / 文件清单

新增：
- `anomaly/StreamId.kt`、`anomaly/AnomalyType.kt`、`anomaly/AnomalyEvent.kt`
- `anomaly/AudioAnomalyDetector.kt`（纯逻辑）
- `view/ScrollingEnvelopeView.kt`、`view/SpectrogramView.kt`、`view/StreamLaneView.kt`、`view/AnomalyListView.kt`、`view/AnomalyLegendView.kt`（底部折叠说明）
- `view/AudioColors.kt`（集中配色 + 声谱图 LUT，供各视图复用）
- 测试 `anomaly/AudioAnomalyDetectorTest.kt`

修改：
- `presenter/AudioView.kt`（接口增删）
- `presenter/AudioPresenter.kt`（aFrameFlow、两路 sample、detector、durationJob、累积异常、finish 传参）
- `view/AudioMonitorView.kt`（重构为：控件区 + 两 StreamLaneView + AnomalyListView，ScrollView，套用新配色）
- `session/RecordingSessionController.kt`（`finish` 增参；session.json 写 anomalies + anomalySource）
- `AudioMonitorModule.kt`（新增设置 `max_duration_sec`；构造 presenter 传 maxDurationSec；应用配色到控件）

删除：
- `view/WaveformView.kt`、`view/SpectrumView.kt`（被滚动视图取代）

## 11. 测试

- `AudioAnomalyDetectorTest`（纯 JVM）：
  - 削波帧序列 → 仅 1 条 `CLIPPING`（事件段合并）。
  - 单次 dB 跳变 → 1 条 `ENERGY_JUMP`；持续高电平不重复。
  - `db < 阈值` 持续 1.2s 且均值 ≤ −60 → 1 条 `SILENCE_DROPOUT`；不足 1s 不报。
  - `db < 阈值` 但均值 −55 持续 0.6s → 1 条 `HIGH_NOISE_FLOOR`，且不报 `SILENCE_DROPOUT`（互斥）。
  - `flush` 关闭录制结束时仍打开的安静段。
- `RecordingSessionControllerTest`：`finish(anomalies...)` 后 `session.json` 含 `streamB.anomalies` 与 `anomalySource`。
- 滚动/声谱/lane/列表视图为 Android 视图，纯绘制，不做单测。

## 12. 已知约束 / 取舍

- 异常检测在 ~16fps 显示帧上跑：可能漏掉 <60ms 的瞬时削波；落盘标注 `source: live@~16fps`。
- `markAnomaly` 标注事件段末列，非精确起点（调试足够）。
- 时长上限必须有限（10–60s），无"不限时"。
- A 路可视化依赖宿主推流；不推则 A lane 空（与录制降级一致）。
- 视觉优化仅限 audiomon 面板。
