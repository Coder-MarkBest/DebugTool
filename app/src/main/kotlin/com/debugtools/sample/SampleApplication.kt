package com.debugtools.sample

import android.app.Application
import com.debugtools.startup.AppStartupMonitor

class SampleApplication : Application() {
    // DebugTools 在 MainActivity 拿到悬浮窗权限后初始化,保存引用供全局访问
    var voiceModule: VoiceAssistantModule? = null

    override fun onCreate() {
        super.onCreate()
        AppStartupMonitor.init(this, appVersion = "1.0")

        // 模拟一条带依赖的启动链路:config -> (asr, nlu) ; net 并行; tts 依赖 asr。
        AppStartupMonitor.track("config") { Thread.sleep(20) }
        AppStartupMonitor.track("net") { Thread.sleep(15) }                 // 无依赖、并行
        AppStartupMonitor.track("asr", listOf("config")) { Thread.sleep(70) } // 慢组件(>50ms)
        try {
            AppStartupMonitor.track("nlu", listOf("config")) {
                Thread.sleep(10); throw IllegalStateException("模型文件缺失")  // 失败也算结束
            }
        } catch (_: Exception) { /* 已记录为 FAILED */ }
        AppStartupMonitor.track("tts", listOf("asr")) { Thread.sleep(25) }
        AppStartupMonitor.complete()
    }
}
