package com.debugtools.sample

import android.app.Application

class SampleApplication : Application() {
    // DebugTools 在 MainActivity 拿到悬浮窗权限后初始化，保存引用供全局访问
    var voiceModule: VoiceAssistantModule? = null
}
