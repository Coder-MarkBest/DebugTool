package com.debugtools.core.ipc

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import com.debugtools.core.ipc.model.CrashInfo
import com.debugtools.core.ipc.model.DebugEvent

class DebugToolsService : Service() {
    private val callbacks = RemoteCallbackList<IDebugToolsCallback>()
    internal val controller = DebugToolsController()

    private val binder = object : IDebugToolsService.Stub() {
        override fun sendEvent(event: DebugEvent) {
            controller.sendEvent(event)
        }
        override fun reportCrash(crash: CrashInfo) {
            controller.reportCrash(crash)
        }
        override fun updateModuleData(moduleId: String, data: Bundle) {
            controller.updateModuleData(moduleId, data)
        }
        override fun registerCallback(callback: IDebugToolsCallback) {
            callbacks.register(callback)
        }
        override fun unregisterCallback(callback: IDebugToolsCallback) {
            callbacks.unregister(callback)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        callbacks.kill()
        super.onDestroy()
    }
}
