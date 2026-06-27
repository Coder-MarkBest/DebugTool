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
