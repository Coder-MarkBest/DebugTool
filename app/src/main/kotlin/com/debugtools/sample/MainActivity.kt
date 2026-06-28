package com.debugtools.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.debugtools.audiomon.AudioMonitorModule
import com.debugtools.startup.StartupMonitorModule
import com.debugtools.core.DebugTools
import com.debugtools.core.ProcessMode
import com.debugtools.core.ipc.model.DebugEvent
import com.debugtools.general.GeneralModule
import com.debugtools.network.NetworkModule
import com.debugtools.okhttp.NetworkCaptureModule
import com.debugtools.perfmon.PerfMonitorModule
import com.debugtools.startup.store.StartupStore
import com.debugtools.timeline.TimelineModule
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_RECORD_AUDIO = 1002
    }

    private val timelineModule = TimelineModule.create(maxSize = 200)
    private val voiceModule = VoiceAssistantModule()
    private val captureModule = NetworkCaptureModule.create()
    private val audioModule = AudioMonitorModule()
    private val perfModule by lazy {
        PerfMonitorModule.builder()
            .addProcessByName(packageName)
            .updateIntervalSec(10)
            .windowMin(30)
            .build()
    }
    private lateinit var captureClient: OkHttpClient
    private var sampleWs: WebSocket? = null
    private var mockEventJob: Job? = null
    private var processedAudioJob: Job? = null
    private var trafficGen: SampleTrafficGenerator? = null
    private var debugToolsInitialized = false
    private var mockIndex = 0

    private lateinit var btnInit: Button
    private lateinit var btnSendEvent: Button
    private lateinit var btnStartFlow: Button
    private lateinit var btnSendHttp: Button
    private lateinit var btnSendWs: Button
    private lateinit var btnCrash: Button
    private lateinit var btnFeedAudio: Button
    private lateinit var btnGenStartup: Button
    private lateinit var btnGenTraffic: Button
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

        root.addView(Button(this).apply {
            text = "①b 授权录音权限"
            setOnClickListener { requestAudioPermission() }
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

        btnSendHttp = Button(this).apply {
            text = "发送 1 个 mock HTTP 请求"
            isEnabled = false
            setOnClickListener { sendMockHttp() }
        }
        root.addView(btnSendHttp)

        btnSendWs = Button(this).apply {
            text = "建立 mock WebSocket + 发 5 帧"
            isEnabled = false
            setOnClickListener { sendMockWebSocket() }
        }
        root.addView(btnSendWs)

        btnGenTraffic = Button(this).apply {
            text = "🌐 生成示例网络流量（本地，离线可用）"
            isEnabled = false
            setOnClickListener { generateSampleTraffic() }
        }
        root.addView(btnGenTraffic)

        btnCrash = Button(this).apply {
            text = "💥 模拟崩溃（测试 CrashInfo 上报）"
            isEnabled = false
            setOnClickListener { simulateCrash() }
        }
        root.addView(btnCrash)

        btnFeedAudio = Button(this).apply {
            text = "🎙️ 模拟推送处理后音频（Stream A）"
            isEnabled = false
            setOnClickListener { toggleProcessedAudio() }
        }
        root.addView(btnFeedAudio)

        btnGenStartup = Button(this).apply {
            text = "🧩 生成示例启动会话（5 条）"
            isEnabled = false
            setOnClickListener { generateSampleStartupSessions() }
        }
        root.addView(btnGenStartup)

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

    private fun requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            toast("录音权限已授予")
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("录音权限已授予")
            } else {
                toast("录音权限被拒绝，音频监控模块将无法工作")
            }
        }
    }

    private fun initDebugTools() {
        if (debugToolsInitialized) { toast("已初始化"); return }
        try {
            (application as SampleApplication).voiceModule = voiceModule
            DebugTools.builder(this)
                .processMode(ProcessMode.ATTACHED)
                .register(captureModule)
                .register(perfModule)
                .register(voiceModule)
                .register(NetworkModule.create("8.8.8.8"))
                .register(timelineModule)
                .register(audioModule)
                .register(StartupMonitorModule())
                .register(
                    GeneralModule.builder(this)
                        .addDiskMonitor(filesDir.absolutePath, intervalMinutes = 5)
                        .addProcessMonitor(listOf(packageName))
                        .build()
                )
                .build()

            captureClient = OkHttpClient.Builder()
                .addInterceptor(captureModule.httpInterceptor())
                .eventListenerFactory(captureModule.eventListenerFactory())
                .build()
            trafficGen = SampleTrafficGenerator(captureClient, captureModule) { msg ->
                runOnUiThread { appendLog(msg) }
            }

            debugToolsInitialized = true
            btnInit.isEnabled = false
            btnSendEvent.isEnabled = true
            btnStartFlow.isEnabled = true
            btnSendHttp.isEnabled = true
            btnSendWs.isEnabled = true
            btnCrash.isEnabled = true
            btnFeedAudio.isEnabled = true
            btnGenStartup.isEnabled = true
            btnGenTraffic.isEnabled = true
            appendLog("✅ DebugTools 初始化成功（ATTACHED 模式）")
            appendLog("   已注册模块: 语音助手 / 网络 / 流程时间线 / 通用 / 音频监控 / 启动链路")
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

    /**
     * 模拟「语音助手处理后」的音频（Stream A）：生成 PCM16 单声道正弦帧并通过
     * [AudioMonitorModule.feedProcessedAudio] 推给 SDK。非录制期推流会被安全忽略，
     * 因此请在「音频监控」页点「开始录制」后，会话目录里才会出现 streamA.wav。
     */
    private fun toggleProcessedAudio() {
        if (processedAudioJob?.isActive == true) {
            processedAudioJob?.cancel()
            processedAudioJob = null
            btnFeedAudio.text = "🎙️ 模拟推送处理后音频（Stream A）"
            appendLog("⏹ 停止推送 Stream A")
            return
        }
        btnFeedAudio.text = "⏹ 停止推送处理后音频"
        appendLog("▶ 推送 Stream A 中（录制时才落盘为 streamA.wav）")
        processedAudioJob = lifecycleScope.launch(Dispatchers.Default) {
            val sampleRate = 16000
            val frameSize = 1024
            val frameDurationMs = frameSize * 1000L / sampleRate // ~64ms，real-time 节奏
            val twoPiF = 2.0 * Math.PI * 440.0 / sampleRate       // 440Hz 正弦
            var phase = 0.0
            while (isActive) {
                val frame = ShortArray(frameSize) {
                    val sample = (Math.sin(phase) * 0.3 * Short.MAX_VALUE).toInt().toShort()
                    phase += twoPiF
                    sample
                }
                audioModule.feedProcessedAudio(frame)
                delay(frameDurationMs)
            }
        }
    }

    private fun sendMockHttp() {
        val request = Request.Builder()
            .url("https://httpbin.org/json")
            .build()
        captureClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnUiThread { appendLog("✗ HTTP 失败: ${e.message}") }
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val code = response.code
                response.close()
                runOnUiThread { appendLog("✓ HTTP $code") }
            }
        })
    }

    private fun sendMockWebSocket() {
        val existing = sampleWs
        if (existing != null) {
            existing.send("ping-${System.currentTimeMillis() % 10000}")
            appendLog("→ WS send ping")
            return
        }
        val request = Request.Builder()
            .url("wss://echo.websocket.events")
            .build()
        sampleWs = captureModule.newWebSocket(captureClient, request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread { appendLog("✓ WS open") }
                for (i in 1..5) {
                    webSocket.send("frame-$i")
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread { appendLog("← WS recv: $text") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runOnUiThread { appendLog("← WS recv bin ${bytes.size}B") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { appendLog("⊘ WS closed $code $reason") }
                sampleWs = null
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { appendLog("✗ WS failure: ${t.message}") }
                sampleWs = null
            }
        })
    }

    private fun simulateCrash() {
        lifecycleScope.launch {
            toast("1秒后抛出异常，观察 DebugTools 是否捕获 CrashInfo...")
            delay(1000)
            throw RuntimeException("模拟崩溃：ASR 引擎内部 NullPointerException at AudioProcessor.process()")
        }
    }

    /** Write 5 varied sample startup sessions to the store so 「启动链路」 is populated without restarting. */
    private fun generateSampleStartupSessions() {
        appendLog("→ 写入示例启动会话…")
        lifecycleScope.launch(Dispatchers.IO) {
            val store = StartupStore(File(filesDir, "startup"))
            SampleStartupSessions.all(System.currentTimeMillis()).forEach { store.save(it) }
            runOnUiThread { appendLog("✅ 已写入 5 条示例启动会话 — 打开「启动链路」Tab 查看") }
        }
    }

    /** Fire a batch of varied HTTP + a WebSocket against a local MockWebServer (offline-safe). */
    private fun generateSampleTraffic() {
        appendLog("→ 生成示例网络流量…")
        lifecycleScope.launch(Dispatchers.IO) { trafficGen?.generate() }
    }

    override fun onDestroy() {
        super.onDestroy()
        trafficGen?.shutdown()
    }

    private fun appendLog(msg: String) {
        logView.text = "${logView.text}\n$msg"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
