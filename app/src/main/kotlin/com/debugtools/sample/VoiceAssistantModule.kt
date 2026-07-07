package com.debugtools.sample

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.core.settings.SettingItem
import com.debugtools.core.settings.SettingsRenderer

/**
 * 模拟业务设置模块，展示所有 5 种原子设置项类型。
 */
class VoiceAssistantModule : DebugModule {
    override val moduleId = "voice_assistant"
    override val tabTitle = "设置项"

    private val renderer = SettingsRenderer()
    private var storage: SettingsStorage? = null
    private var sessionCount = 0

    override fun buildSettings() = listOf(
        SettingGroup(
            title = "识别设置",
            items = listOf(
                SettingItem.SingleSelect(
                    key = "log_level",
                    label = "日志级别",
                    options = listOf("Debug", "Info", "Warn", "Error"),
                    default = "Debug",
                    description = "选择 Debug 会输出所有日志，可能影响性能"
                ),
                SettingItem.Toggle(
                    key = "asr_enabled",
                    label = "启用 ASR",
                    default = true
                ),
                SettingItem.EditText(
                    key = "asr_server",
                    label = "ASR 服务地址",
                    default = "ws://asr.example.com:8080",
                    hint = "ws:// 或 wss://",
                    description = "语音识别 WebSocket 服务端地址"
                )
            )
        ),
        SettingGroup(
            title = "功能开关",
            items = listOf(
                SettingItem.MultiSelect(
                    key = "audio_features",
                    label = "音频处理",
                    options = listOf("降噪", "回声消除", "声纹识别", "VAD 检测"),
                    defaults = listOf("降噪", "VAD 检测"),
                    description = "同时开启多项功能会增加 CPU 占用"
                ),
                SettingItem.Custom(
                    key = "custom_info",
                    label = "SDK 信息",
                    viewFactory = { context, _ ->
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(24, 12, 24, 12)
                            addView(TextView(context).apply {
                                text = "DebugTools v1.0.0"
                                setTextColor(Color.parseColor("#63B3ED"))
                                textSize = 12f
                            })
                            addView(TextView(context).apply {
                                text = "Build: ${System.currentTimeMillis() / 1000}"
                                setTextColor(Color.GRAY)
                                textSize = 10f
                            })
                        }
                    }
                )
            )
        )
    )

    override fun createContentView(context: Context): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val statusBar = TextView(context).apply {
            text = "会话次数: $sessionCount"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2D3748"))
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(statusBar)
        val s = storage
        if (s != null) {
            root.addView(renderer.render(context, buildSettings(), s))
        }
        return root
    }

    override fun getBriefItems() = listOf(
        BriefItem(
            text = "ASR ${if (storage?.getBoolean("asr_enabled", true) == true) "ON" else "OFF"}",
            color = if (storage?.getBoolean("asr_enabled", true) == true)
                Color.parseColor("#68D391") else Color.GRAY
        ),
        BriefItem("会话 $sessionCount")
    )

    override fun onAttach(context: Context, storage: SettingsStorage) {
        this.storage = storage
    }

    override fun onDetach() {
        storage = null
    }

    fun incrementSession() { sessionCount++ }
}
