package com.debugtools.sample

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.debugtools.core.DebugTools
import com.debugtools.core.ProcessMode
import com.debugtools.core.ipc.model.DebugEvent
import com.debugtools.general.GeneralModule
import com.debugtools.network.NetworkModule
import com.debugtools.timeline.TimelineModule
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val timelineModule = TimelineModule.create(maxSize = 200)
    private val voiceModule = VoiceAssistantModule()
    private var mockEventJob: Job? = null
    private var debugToolsInitialized = false
    private var mockIndex = 0

    private lateinit var btnInit: Button
    private lateinit var btnSendEvent: Button
    private lateinit var btnStartFlow: Button
    private lateinit var btnCrash: Button
    private lateinit var logView: TextView

    // 模拟语音助手的一次完整对话流程
    private val mockDialogueFlow = listOf(
        DebugEvent(0, "App 启动", "pid=${android.os.Process.myPid()}"),
        DebugEvent(0, "DebugTools 初始化完成"),
        DebugEvent(0, "语音引擎加载中..."),
        DebugEvent(0, "ASR 引擎就绪", "模型: zh_CN_v3, 加载耗时: 320ms"),
        DebugEvent(0, "NLU 引擎就绪", "模型: intent_v2.1"),
        DebugEvent(0, "用户按下唤醒键"),
        DebugEvent(0, "VAD: 检测到语音开始"),
        DebugEvent(0, "ASR 流式识别中...", "chunk_size=960, sample_rate=16000"),
        DebugEvent(0, "ASR 中间结果: \"你好\""),
        DebugEvent(0, "VAD: 检测到语音结束", "duration=1.8s"),
        DebugEvent(0, "ASR 最终结果: \"你好，小爱\"", "confidence=0.97, latency=85ms"),
        DebugEvent(0, "NLU 处理中", "input=你好，小爱"),
        DebugEvent(0, "NLU 结果: 问候语", "intent=greeting, entities=[], confidence=0.99"),
        DebugEvent(0, "TTS 合成: \"你好！有什么可以帮你？\"", "voice=xiaoai_v3, speed=1.0"),
        DebugEvent(0, "TTS 播放完成", "duration=1.2s"),
        DebugEvent(0, "等待下一轮唤醒...")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "DebugTools 调试 Demo"
            textSize = 20f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "需要「悬浮在其他应用上方」权限才能展示调试工具"
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 24)
        })

        root.addView(Button(this).apply {
            text = "① 授权悬浮窗权限"
            setOnClickListener { requestOverlayPermission() }
        })

        btnInit = Button(this).apply {
            text = "② 初始化 DebugTools"
            isEnabled = Settings.canDrawOverlays(this@MainActivity)
            setOnClickListener { initDebugTools() }
        }
        root.addView(btnInit)

        btnSendEvent = Button(this).apply {
            text = "发送单条 Mock 事件"
            isEnabled = false
            setOnClickListener { sendSingleMockEvent() }
        }
        root.addView(btnSendEvent)

        btnStartFlow = Button(this).apply {
            text = "▶ 自动模拟完整对话流程"
            isEnabled = false
            setOnClickListener { toggleMockFlow() }
        }
        root.addView(btnStartFlow)

        btnCrash = Button(this).apply {
            text = "💥 模拟崩溃（测试 CrashInfo 上报）"
            isEnabled = false
            setOnClickListener { simulateCrash() }
        }
        root.addView(btnCrash)

        logView = TextView(this).apply {
            text = "--- 日志 ---"
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(0, 24, 0, 0)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        btnInit.isEnabled = Settings.canDrawOverlays(this) && !debugToolsInitialized
    }

    private fun requestOverlayPermission() {
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    private fun initDebugTools() {
        if (debugToolsInitialized) { toast("已初始化"); return }
        try {
            (application as SampleApplication).voiceModule = voiceModule
            DebugTools.builder(this)
                .processMode(ProcessMode.ATTACHED)
                .register(voiceModule)
                .register(NetworkModule.create("8.8.8.8"))
                .register(timelineModule)
                .register(
                    GeneralModule.builder(this)
                        .addDiskMonitor(filesDir.absolutePath, intervalMinutes = 5)
                        .addProcessMonitor(listOf(packageName))
                        .build()
                )
                .build()

            debugToolsInitialized = true
            btnInit.isEnabled = false
            btnSendEvent.isEnabled = true
            btnStartFlow.isEnabled = true
            btnCrash.isEnabled = true
            appendLog("✅ DebugTools 初始化成功（ATTACHED 模式）")
            appendLog("   已注册模块: 语音助手 / 网络 / 流程时间线 / 通用")
        } catch (e: Exception) {
            appendLog("❌ 初始化失败: ${e.message}")
        }
    }

    private fun sendSingleMockEvent() {
        val template = mockDialogueFlow[mockIndex % mockDialogueFlow.size]
        val event = DebugEvent(
            timestamp = System.currentTimeMillis(),
            tag = template.tag,
            detail = template.detail
        )
        timelineModule.onEvent(event)
        if (template.tag.contains("ASR 最终")) voiceModule.incrementSession()
        appendLog("→ ${event.tag}")
        mockIndex++
    }

    private fun toggleMockFlow() {
        if (mockEventJob?.isActive == true) {
            mockEventJob?.cancel()
            btnStartFlow.text = "▶ 自动模拟完整对话流程"
        } else {
            startMockFlow()
            btnStartFlow.text = "⏹ 停止模拟"
        }
    }

    private fun startMockFlow() {
        mockIndex = 0
        mockEventJob = lifecycleScope.launch {
            appendLog("--- 开始模拟对话流程 ---")
            mockDialogueFlow.forEach { template ->
                val event = DebugEvent(
                    timestamp = System.currentTimeMillis(),
                    tag = template.tag,
                    detail = template.detail
                )
                timelineModule.onEvent(event)
                if (template.tag.contains("ASR 最终")) voiceModule.incrementSession()
                appendLog("→ ${event.tag}")
                delay(600L)
            }
            appendLog("--- 一轮对话完成 ---")
            btnStartFlow.text = "▶ 自动模拟完整对话流程"
        }
    }

    private fun simulateCrash() {
        lifecycleScope.launch {
            toast("1秒后抛出异常，观察 DebugTools 是否捕获 CrashInfo...")
            delay(1000)
            throw RuntimeException("模拟崩溃：ASR 引擎内部 NullPointerException at AudioProcessor.process()")
        }
    }

    private fun appendLog(msg: String) {
        logView.text = "${logView.text}\n$msg"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
