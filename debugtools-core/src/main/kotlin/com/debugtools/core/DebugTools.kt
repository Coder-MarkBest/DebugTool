package com.debugtools.core

import android.content.Context
import com.debugtools.core.ipc.DebugToolsClient
import com.debugtools.core.ipc.DebugToolsController
import com.debugtools.core.ipc.model.CrashInfo
import com.debugtools.core.ipc.model.DebugEvent
import com.debugtools.core.module.DebugModule
import com.debugtools.core.module.ModuleRegistry
import com.debugtools.core.persistence.ScopedStorage
import com.debugtools.core.window.BriefOrientation
import com.debugtools.core.window.DisplayModeManager
import com.debugtools.core.window.FloatingWindowManager

class DebugTools private constructor(private val builder: DebugToolsBuilder) {
    private val registry = ModuleRegistry()
    private val modeManager = DisplayModeManager()
    private val controller = DebugToolsController()
    private var client: DebugToolsClient? = null
    private var windowManager: FloatingWindowManager? = null

    init {
        builder.modules.forEach { module ->
            registry.register(module)
            module.onAttach(builder.context, ScopedStorage(module.moduleId, builder.storage))
        }
        when (builder.processMode) {
            ProcessMode.ATTACHED    -> setupAttached(builder.context, builder.briefOrientation)
            ProcessMode.INDEPENDENT -> setupIndependent(builder.context)
        }
        installCrashHandler()
    }

    private fun setupAttached(context: Context, briefOrientation: BriefOrientation) {
        windowManager = FloatingWindowManager(context, modeManager, briefOrientation).also {
            it.init(registry.modules)
        }
    }

    private fun setupIndependent(context: Context) {
        client = DebugToolsClient(context).also { it.connect() }
    }

    private fun installCrashHandler() {
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crash = CrashInfo(
                timestamp = System.currentTimeMillis(),
                threadName = thread.name,
                exceptionClass = throwable::class.java.name,
                message = throwable.message,
                stackTrace = throwable.stackTraceToString()
            )
            client?.reportCrash(crash)  // sync binder call before process dies
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    fun sendEvent(event: DebugEvent) {
        when (builder.processMode) {
            ProcessMode.ATTACHED    -> controller.sendEvent(event)
            ProcessMode.INDEPENDENT -> client?.sendEvent(event)
        }
    }

    fun destroy() {
        registry.modules.forEach { it.onDetach() }
        windowManager?.destroy()
        client?.disconnect()
    }

    companion object {
        @Volatile private var instance: DebugTools? = null

        fun builder(context: Context): DebugToolsBuilder =
            DebugToolsBuilder(context.applicationContext)

        internal fun create(builder: DebugToolsBuilder): DebugTools =
            DebugTools(builder).also { instance = it }

        fun sendEvent(event: DebugEvent) {
            instance?.sendEvent(event)
                ?: error("DebugTools not initialized. Call DebugTools.builder(context).build() first.")
        }
    }
}
