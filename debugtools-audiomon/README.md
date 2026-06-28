# debugtools-audiomon

语音助手音频 Debug 模块：在 DebugTools 悬浮窗中实时显示麦克风波形与 FFT 频谱，并支持**双路录制会话**——把「语音助手处理后的音频」和「DebugTool 自采集的麦克风原始音频」分别落盘，各自提取数值特性，预留网络上报接口。

## 能力概览

- **双路滚动可视化**：A/B 两路各「dB 能量包络 + 声谱图」，最近 ~10s 窗口实时滚动。
- **实时异常检测**：削波 / 异常静音 / 能量突变 / 底噪偏高，图上标注 + 底部列表（时间+类型+详情），并写入 `session.json`（`anomalySource: live@~16fps`）。底部「异常类型说明」可折叠查看每类问题含义。
- **录制时长上限**：设置 `max_duration_sec`（10/20/30/40/50/60 秒），到时自动结束并落盘；状态栏显示倒计时。
- **双路录制会话**：点一次「开始录制 → 结束录制」为一个会话，产物写入独立目录：
  - `streamB.wav` —— DebugTool 自采集（麦克风原始音，始终存在）
  - `streamA.wav` —— 语音助手处理后音频（宿主推入才有，可选）
  - `streamB.features.json` / `streamA.features.json` —— 各路数值特性
  - `session.json` —— 会话元数据（起止时间、采样率、各路是否存在、特性摘要、各路异常列表 `anomalies`、`anomalySource`）
- **数值特性**（每路独立，整段汇总）：基础幅度（时长/RMS/峰值/dB）、静音与活动占比、频谱特征（主频/质心/分频段能量）、逐帧 RMS/dB 时序曲线。
- **网络上报**：SDK 只负责落盘并在会话结束时回调宿主实现的 `AudioReporter`；**具体怎么上传由接入方自己实现**。

## 数据来源（两路音频）

| 流 | 来源 | 如何进入 SDK |
|----|------|-------------|
| Stream B | DebugTool 自己采集的麦克风原始音 | SDK 内部自动采集（需 `RECORD_AUDIO` 权限）|
| Stream A | 语音助手 AEC/降噪/波束成形之后的输出 | 宿主在自己的音频回调里实时调用 `feedProcessedAudio(frame)` 推入 |

> 不做两路对比/对齐；两路各自独立统计。

## 接入步骤

### 1. 实现上报接口

```kotlin
import com.debugtools.audiomon.report.AudioReporter
import com.debugtools.audiomon.report.AudioReportData

val reporter = object : AudioReporter {
    // SDK 在 IO 线程回调；自行决定上传方式（OkHttp / 自有通道 / 不传）。
    // 抛异常会被 SDK 捕获并在面板提示，不影响录制。
    override fun report(session: AudioReportData) {
        upload(session.metadata)        // session.json
        session.streamBWav?.let { upload(it) }
        session.streamBFeatures?.let { upload(it) }
        session.streamAWav?.let { upload(it) }       // A 路可能为 null
        session.streamAFeatures?.let { upload(it) }
    }
}
```

### 2. 注册时注入 reporter

```kotlin
val audioModule = AudioMonitorModule(reporter = reporter)   // reporter 可空
DebugTools.builder(context).register(audioModule).build()
```

`reporter` 为空时仍可录制与落盘，只是面板上的「上报」按钮置灰并提示「未配置上报接口」。

### 3. 在音频处理回调里推入 Stream A

```kotlin
// 宿主的音频处理线程：处理后立即把 PCM16 单声道帧推给 SDK。
// 仅在录制进行中被消费；非录制期调用安全忽略。
audioModule.feedProcessedAudio(processedFrame)   // ShortArray, PCM16 mono
```

未推 Stream A 时，会话只含 B 路，功能正常降级。

## 上报时机

- **自动**：在设置中开启「结束后自动上报」(`auto_report`)，每次结束录制后自动调用 `reporter.report(...)`。
- **手动**：面板上展示最近会话，点「上报最近会话」按钮手动触发。

## 设置项

| key | 含义 | 默认 |
|-----|------|------|
| `sample_rate` | 麦克风采样率 (Hz) | `16000` |
| `save_dir` | 会话根目录 | `getExternalFilesDir(MUSIC)` |
| `silence_threshold_db` | 静音判定阈值 (dB) | `-50` |
| `auto_report` | 结束录制后自动上报 | `false` |
| `max_duration_sec` | 录制时长上限（秒，到时自动结束并落盘） | `10` |

> 采样率一致性由宿主保证：`feedProcessedAudio` 推入的帧应与 `sample_rate` 一致，否则需宿主自行重采样。

## 约束

- **仅 ATTACHED 进程模式**：`feedProcessedAudio` 依赖宿主与模块在同进程持有同一实例。INDEPENDENT（`:debug`）模式下跨进程推流不支持，调用会被安全忽略。
- 需要 `RECORD_AUDIO` 运行时权限（采集 Stream B）；权限缺失时面板会提示。
