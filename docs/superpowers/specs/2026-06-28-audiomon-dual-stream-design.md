# 设计：双路音频录制 + 特性提取 + 上报（audiomon 扩展）

日期：2026-06-28
模块：`debugtools-audiomon`
状态：已批准，待实现

## 1. 背景与目标

`debugtools-audiomon` 现状：实时麦克风波形 + FFT 频谱显示，可选保存单路 WAV。

本次扩展面向**语音助手 Debug 场景**，引入两路音频的录制、特性提取与落盘，并预留网络上报接口：

- **Stream A**——语音助手处理后的音频（AEC / 降噪 / 波束成形之后的输出），由宿主 App 实时推入 SDK。
- **Stream B**——DebugTool 自己从麦克风采集的原始音频（复用现有 `AudioRecorderWrapper`）。

每次「开始录制 → 结束录制」为一个**会话（session）**。结束时，两路各自落盘为 WAV，并由 SDK **各自**提取数值特性写入磁盘；随后通过宿主实现的上报接口把会话产物交出去。

**明确不做（本次范围外）：**
- 不做两路之间的对比 / 对齐 / 差异计算（已与需求方确认去掉）。
- 不支持 INDEPENDENT（`:debug`）进程模式下的跨进程推流——见第 8 节约束。

## 2. 录制会话与目录结构

一次会话的全部产物落在 `rootDir` 下的独立子目录：

```
<rootDir>/
  20260628_143052_a1b2/            # sessionId = yyyyMMdd_HHmmss_<4位随机后缀>
    streamB.wav                    # DebugTool 自采集（必有）
    streamB.features.json
    streamA.wav                    # 助手处理后音频（宿主推入才有，可选）
    streamA.features.json
    session.json                   # 会话元数据
```

- `rootDir` 复用现有设置项 `save_dir`（默认 `getExternalFilesDir(DIRECTORY_MUSIC)`）。
- Stream A 文件仅在该会话期间宿主调用过 `feedProcessedAudio(...)` 时才生成。未推流则该会话只有 B 路文件，且 `session.json` 中 `streamA.present = false`。
- `sessionId` 的随机后缀避免同秒内多次录制目录冲突。随机后缀生成不依赖 `Math.random()`/`Date.now()` 限制（这是运行期 Android 代码，非 workflow 脚本），用 `Random` 或 `UUID` 取 4 位即可。

### session.json 结构

```json
{
  "sessionId": "20260628_143052_a1b2",
  "startTime": 1751101852000,
  "endTime": 1751101871000,
  "durationMs": 19000,
  "sampleRate": 16000,
  "streams": {
    "streamB": {
      "present": true,
      "wav": "streamB.wav",
      "features": "streamB.features.json",
      "summary": { "durationMs": 19000, "avgDb": -28.4, "peakDb": -6.1, "activeRatio": 0.62 }
    },
    "streamA": {
      "present": true,
      "wav": "streamA.wav",
      "features": "streamA.features.json",
      "summary": { "durationMs": 18800, "avgDb": -22.1, "peakDb": -3.0, "activeRatio": 0.66 }
    }
  }
}
```

`summary` 只放少量关键值，方便上报端不解析大文件即可展示概览。

## 3. 公开接口（宿主集成面）

### 3.1 推入 Stream A

```kotlin
/**
 * 由宿主在自己的音频处理回调里调用，推入“助手处理后”的 PCM16 单声道帧。
 * 仅在录制进行中（session 打开）时被消费；非录制期调用直接忽略。
 * 线程安全：可从宿主的音频线程调用。
 */
fun feedProcessedAudio(frame: ShortArray)
```

- 定义在 `AudioMonitorModule` 实例上。宿主持有 module 引用即可推流。
- 与 Stream B 同期采集：SDK 不做严格时间对齐，仅各自顺序写入与统计。
- 采样率假定与 SDK 设置的 `sample_rate` 一致；不一致由宿主负责重采样（文档注明）。

### 3.2 上报接口

```kotlin
interface AudioReporter {
    /**
     * 录制会话产物就绪后由 SDK 在 IO 线程调用。
     * 宿主自行决定上传方式（OkHttp / 自有通道 / 不上传）。
     * 抛异常会被 SDK 捕获并在面板提示，不影响录制流程。
     */
    fun report(session: AudioReportData)
}

data class AudioReportData(
    val sessionId: String,
    val sessionDir: File,
    val streamBWav: File?,
    val streamBFeatures: File?,
    val streamAWav: File?,
    val streamAFeatures: File?,
    val metadata: File          // session.json
)
```

### 3.3 注入

```kotlin
val audioModule = AudioMonitorModule(reporter = myReporter)   // reporter 可空
DebugTools.builder(context).register(audioModule).build()
// 录制期：audioModule.feedProcessedAudio(frame)
```

`reporter` 为构造参数，默认 `null`。为 `null` 时上报按钮提示「未配置上报接口」，自动上报跳过。

## 4. 特性提取（SDK 内置，流式累积）

新增 `AudioFeatureExtractor`，对每路音频**边录边累积**，避免在内存中囤积整段 PCM。结束录制时汇总成 4 类，序列化为 `*.features.json`。

| 类别 | 字段 | 算法 |
|------|------|------|
| 基础幅度 | `durationMs`、`sampleCount`、`avgRms`、`peakAmplitude`、`avgDb`、`peakDb` | running sum / max |
| 静音/活动 | `zeroCrossingRate`、`silenceRatio`、`activeRatio` | 逐帧能量与 `silence_threshold_db` 比较计数 |
| 频谱 | `dominantFreq`、`spectralCentroid`、`bandEnergy[]` | 复用 `FftProcessor.computeMagnitudes`，逐帧幅度谱累加求平均后统计 |
| 逐帧时序 | `rmsSeries[]`、`dbSeries[]` | 每帧一个值追加 |

**实现要点：**
- running sums（rms 平方和、peak、过零数、静音帧计数、平均幅度谱累加器）内存恒定，与时长无关。
- 仅 `rmsSeries` / `dbSeries` 随时长线性增长（每帧一个 float），可接受。
- 「帧」按 `fftSize`（默认值见 `AudioRecorderWrapper`）切分；不足一帧的尾部数据计入基础统计但不参与频谱。
- 序列化用 Android 内置 `org.json`，**不引入新依赖**。
- `AudioFeatureExtractor` 设计为可独立单测：`feed(frame)` 累积、`build(): AudioFeatures` 汇总、`AudioFeatures.toJson()` 序列化，均不触碰 Android 框架类。

`bandEnergy[]` 频段划分：固定 **8 个等宽频段**（`0 ~ sampleRate/2` 均分为 8 段），定义为 `AudioFeatureExtractor.BAND_COUNT = 8` 常量。

## 5. 录制编排与 UI

### 5.1 编排（扩展 `AudioPresenter` 或新增 `RecordingSessionController`）

录制会话生命周期：

1. **开始录制**：创建 sessionDir；为 B 路打开 `WavFileWriter` + `AudioFeatureExtractor`；标记 session 打开，使 `feedProcessedAudio` 开始为 A 路惰性创建 writer/extractor（首帧到达时才建 A 路文件）；启动 B 路采集流（现有 `AudioRecorderWrapper.audioStream`）。
2. **录制中**：B 路每个 PCM buffer → 写 WAV + 喂 extractor + 现有 UI 波形/频谱显示；A 路每个推入帧 → 写 WAV + 喂 extractor。
3. **结束录制**：finalize 两路 WAV header → 各 extractor `build()` 并写 `*.features.json` → 写 `session.json` →（若 `auto_report` 开启且 reporter 非空）IO 线程调 `reporter.report(data)`，捕获异常并提示。

### 5.2 UI（`AudioMonitorView` 扩展）

- 现有 toggle 按钮文案/语义升级为 **「开始录制 / 结束录制」**。
- 录制结束后在面板展示**最近会话列表**（至少最近 1 条，含 sessionId、时长、两路 avgDb 概览），每条带一个 **「上报」** 按钮手动触发 `reporter.report(data)`。
- reporter 为 null 时上报按钮置灰/提示「未配置上报接口」。
- 录音权限缺失时沿用现有提示逻辑。

## 6. 设置项（扩展 `buildSettings`）

保留：`sample_rate`、`save_dir`（`save_dir` 即 `rootDir`）。

**移除 `save_enabled`**：新的会话模型下，录制本身就是为了落盘（两路 WAV + 特性 + session.json），文件持久化是功能内核，不再由开关门控，否则 `save_enabled=false` 语义自相矛盾。

新增：

| key | 类型 | 默认 | 说明 |
|-----|------|------|------|
| `auto_report` | Toggle | false | 结束录制后自动调用上报接口 |
| `silence_threshold_db` | SingleSelect | "-50" | 静音判定阈值（dB），用于 silenceRatio/activeRatio；选项如 -40/-50/-60 |

## 7. 测试

- `AudioFeatureExtractorTest`：喂合成 PCM——
  - 全静音 → `silenceRatio≈1`、`avgDb` 极低；
  - 单频正弦 → `dominantFreq` 命中该频率（容差内）、`peakAmplitude` 正确；
  - 已知幅度方波/常量 → `avgRms` / `avgDb` 数值正确。
- `session.json` 与 `*.features.json` 结构往返测试（写出后用 `org.json` 读回校验字段）。
- 沿用现有 `WavFileWriterTest` / `FftProcessorTest` 风格（纯 JVM 单测，不依赖 Android instrumentation）。

## 8. 已知约束与取舍

- **仅 ATTACHED 模式**：`feedProcessedAudio` 依赖宿主与 module 在同进程持有引用。INDEPENDENT（`:debug`）模式下跨进程推流不在本次范围（与 CLAUDE.md 中 AIDL 进程约束同类取舍）。实现时若检测到 INDEPENDENT 模式，`feedProcessedAudio` 应安全忽略并可日志告警。
- **不做时间对齐 / 对比**：两路独立统计，互不参照。
- **采样率一致性**由宿主保证；A/B 采样率不一致时特性数值不可直接横向比较（文档注明）。
- **Stream A 可选**：宿主不推流时会话仅含 B 路，功能正常降级。

## 9. 涉及文件（预估）

- 新增 `audio/AudioFeatureExtractor.kt`、`audio/AudioFeatures.kt`（数据类 + toJson）
- 新增 `report/AudioReporter.kt`、`report/AudioReportData.kt`
- 新增 `session/RecordingSessionController.kt`（或并入 `AudioPresenter`）
- 修改 `AudioMonitorModule.kt`（构造参数 reporter、`feedProcessedAudio`、新设置项）
- 修改 `AudioPresenter.kt`（双路编排、会话落盘、上报触发）
- 修改 `view/AudioMonitorView.kt`（按钮文案、最近会话 + 上报按钮）
- 新增测试 `AudioFeatureExtractorTest.kt` 等
