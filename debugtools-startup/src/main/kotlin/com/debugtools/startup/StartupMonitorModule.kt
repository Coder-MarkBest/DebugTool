package com.debugtools.startup

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.startup.protocol.StartupSession
import com.debugtools.startup.protocol.StepStatus
import com.debugtools.startup.store.StartupStore
import com.debugtools.startup.view.StartupRootView
import java.io.File

/**
 * Debug module that shows captured app-startup sessions (persisted + the current one).
 *
 * The host reports via [AppStartupMonitor] from Application.onCreate; this module just
 * reads and visualizes. Register it like any other module:
 * ```kotlin
 * DebugTools.builder(context).register(StartupMonitorModule()).build()
 * ```
 */
class StartupMonitorModule : DebugModule {

    override val moduleId: String = "startup"
    override val tabTitle: String = "启动链路"

    private var appContext: Context? = null

    override fun onAttach(context: Context, storage: SettingsStorage) {
        appContext = context.applicationContext
    }

    override fun onDetach() { appContext = null }

    override fun createContentView(context: Context): View {
        val store = StartupStore(File(context.applicationContext.filesDir, "startup"))
        val persisted = store.load()
        val current = AppStartupMonitor.currentSession()
        val sessions = mergeCurrent(current, persisted)
        return StartupRootView(context, sessions)
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun getBriefItems(): List<BriefItem> {
        val current = AppStartupMonitor.currentSession() ?: return emptyList()
        val fail = current.steps.count { it.status == StepStatus.FAILED }
        return listOf(BriefItem(text = "启动 ${current.steps.size}步" + if (fail > 0) " · ${fail}失败" else ""))
    }

    private fun mergeCurrent(current: StartupSession?, persisted: List<StartupSession>): List<StartupSession> {
        if (current == null) return persisted
        val rest = persisted.filter { it.sessionId != current.sessionId }
        return listOf(current) + rest
    }
}
