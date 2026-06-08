package com.debugtools.core.ipc

import android.os.Bundle
import com.debugtools.core.ipc.model.CrashInfo
import com.debugtools.core.ipc.model.DebugEvent

internal class DebugToolsController {
    private var eventListener: ((DebugEvent) -> Unit)? = null
    private var crashListener: ((CrashInfo) -> Unit)? = null
    private var moduleDataListener: ((moduleId: String, data: Bundle) -> Unit)? = null

    fun setEventListener(listener: (DebugEvent) -> Unit) { eventListener = listener }
    fun setCrashListener(listener: (CrashInfo) -> Unit) { crashListener = listener }
    fun setModuleDataListener(listener: (String, Bundle) -> Unit) { moduleDataListener = listener }

    fun sendEvent(event: DebugEvent) { eventListener?.invoke(event) }
    fun reportCrash(crash: CrashInfo) { crashListener?.invoke(crash) }
    fun updateModuleData(moduleId: String, data: Bundle) { moduleDataListener?.invoke(moduleId, data) }
}
