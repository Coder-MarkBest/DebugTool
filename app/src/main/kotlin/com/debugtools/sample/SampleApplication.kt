package com.debugtools.sample

import android.app.Application
import com.debugtools.startup.AppStartupMonitor
import com.debugtools.startupinit.StartupInitFlow
import com.debugtools.startupinit.debugtools.reportToStartupMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SampleApplication : Application() {
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // DebugTools 在 MainActivity 拿到悬浮窗权限后初始化,保存引用供全局访问
    var voiceModule: VoiceAssistantModule? = null

    override fun onCreate() {
        super.onCreate()
        AppStartupMonitor.init(this, appVersion = "1.0")

        initScope.launch {
            StartupInitFlow.builder()
                .task("config") { runBlockingInit { Thread.sleep(20) } }
                .task("net") { runBlockingInit { Thread.sleep(15) } }
                .task("asr", dependsOn = listOf("config")) { runBlockingInit { Thread.sleep(70) } }
                .task("nlu", dependsOn = listOf("config")) {
                    runBlockingInit {
                        Thread.sleep(10)
                        throw IllegalStateException("模型文件缺失")
                    }
                }
                .task("tts", dependsOn = listOf("asr")) { runBlockingInit { Thread.sleep(25) } }
                .reportToStartupMonitor()
                .run()
        }
    }
}
