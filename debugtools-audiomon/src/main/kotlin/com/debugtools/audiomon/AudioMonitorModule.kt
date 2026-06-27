package com.debugtools.audiomon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Environment
import android.view.View
import com.debugtools.audiomon.presenter.AudioPresenter
import com.debugtools.audiomon.view.AudioMonitorView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.core.settings.SettingItem
import java.io.File

/**
 * Debug module for real-time microphone audio monitoring.
 *
 * Displays an oscilloscope-style waveform and FFT frequency spectrum
 * in the DebugTools overlay panel. Optionally saves recordings as WAV files.
 * Requires RECORD_AUDIO runtime permission.
 *
 * Usage:
 * ```kotlin
 * DebugTools.builder(context)
 *     .register(AudioMonitorModule())
 *     .build()
 * ```
 */
class AudioMonitorModule : DebugModule {

    override val moduleId: String = "audiomon"
    override val tabTitle: String = "音频监控"

    private var presenter: AudioPresenter? = null
    private var monitorView: AudioMonitorView? = null
    private var appContext: Context? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        this.appContext = context.applicationContext
        val sampleRate = storage.getString("sample_rate", "16000").toIntOrNull() ?: 16000
        val saveEnabled = storage.getBoolean("save_enabled", true)
        val savePath = storage.getString("save_dir", getDefaultSaveDir(context).absolutePath)

        val saveDir = File(savePath).let { dir ->
            if (!dir.exists()) dir.mkdirs()
            dir
        }

        presenter = AudioPresenter(
            sampleRate = sampleRate,
            saveDir = saveDir,
            saveEnabled = saveEnabled
        )
    }

    override fun onDetach() {
        presenter?.detach()
        presenter = null
        monitorView = null
        appContext = null
    }

    override fun createContentView(context: Context): View {
        return AudioMonitorView(context).also { view ->
            monitorView = view
            presenter?.attach(view)

            // Wire toggle button
            view.setToggleListener {
                if (!hasRecordPermission(context)) {
                    view.showStatus("⚠️ 需要录音权限 — 请先在宿主 Activity 中授权")
                    return@setToggleListener
                }
                presenter?.toggleMonitoring()
            }

            // Show permission status
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
                    SettingItem.Toggle(
                        key = "save_enabled",
                        label = "保存录音文件",
                        default = true,
                        description = "录音时自动保存为 WAV 文件"
                    ),
                    SettingItem.EditText(
                        key = "save_dir",
                        label = "录音保存目录",
                        default = defaultDir,
                        hint = "例如: /sdcard/DebugTools/audio",
                        description = "WAV 文件的保存路径，修改后下次录音生效"
                    )
                )
            )
        )
    }

    override fun getBriefItems(): List<BriefItem> {
        val active = presenter?.monitoring == true
        return listOf(
            BriefItem(
                text = if (active) "🎙️ 录音中" else "⏸ 已停止",
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
