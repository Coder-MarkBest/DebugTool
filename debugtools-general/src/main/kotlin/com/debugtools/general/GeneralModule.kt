package com.debugtools.general

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class GeneralModule private constructor(
    private val diskMonitors: List<DiskMonitor>,
    private val processMonitors: List<ProcessMonitor>
) : DebugModule, GeneralView {
    override val moduleId = "debugtools_general"
    override val tabTitle = "通用"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var presenter: GeneralPresenter? = null
    private var diskSizes: List<Pair<String, Long>> = emptyList()
    private var processStates: List<Pair<String, Boolean>> = emptyList()
    private var contentRoot: LinearLayout? = null

    override fun buildSettings() = emptyList<SettingGroup>()

    override fun createContentView(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }.also { root ->
            contentRoot = root
            presenter = GeneralPresenter(diskMonitors, processMonitors, scope).also {
                it.attachView(this)
            }
        }
    }

    override fun getBriefItems(): List<BriefItem> {
        val diskItems = diskSizes.map { (path, size) ->
            BriefItem("${path.substringAfterLast('/')}: ${formatSize(size)}")
        }
        val procItems = processStates.map { (name, alive) ->
            BriefItem(
                "${name.substringAfterLast('.')} ${if (alive) "✓" else "✗"}",
                color = if (alive) Color.parseColor("#68D391") else Color.parseColor("#FC8181")
            )
        }
        return diskItems + procItems
    }

    override fun onAttach(context: Context, storage: SettingsStorage) {
        diskMonitors.forEach { it.start(scope) }
        processMonitors.forEach { it.start(context, scope) }
    }

    override fun onDetach() {
        presenter?.detach()
        scope.cancel()
    }

    override fun showDiskSizes(sizes: List<Pair<String, Long>>) {
        diskSizes = sizes
        rebuildView()
    }

    override fun showProcessStates(states: List<Pair<String, Boolean>>) {
        processStates = states
        rebuildView()
    }

    private fun rebuildView() {
        val root = contentRoot ?: return
        root.removeAllViews()
        diskSizes.forEach { (path, size) ->
            root.addView(TextView(root.context).apply {
                text = "$path\n${formatSize(size)}"
                setPadding(0, 8, 0, 8)
            })
        }
        processStates.forEach { (name, alive) ->
            root.addView(TextView(root.context).apply {
                text = "$name  ${if (alive) "● 运行中" else "✗ 未运行"}"
                setTextColor(if (alive) Color.parseColor("#68D391") else Color.parseColor("#FC8181"))
                setPadding(0, 8, 0, 8)
            })
        }
    }

    private fun formatSize(bytes: Long) = when {
        bytes < 1024L               -> "${bytes}B"
        bytes < 1024L * 1024        -> "${bytes / 1024}KB"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024)}MB"
        else                        -> "${bytes / (1024L * 1024 * 1024)}GB"
    }

    class Builder(private val context: Context) {
        private val diskMonitors = mutableListOf<DiskMonitor>()
        private val processMonitors = mutableListOf<ProcessMonitor>()

        fun addDiskMonitor(path: String, intervalMinutes: Int = 10) = apply {
            diskMonitors.add(DiskMonitor(path, intervalMinutes))
        }
        fun addProcessMonitor(processNames: List<String>, checkIntervalMs: Long = 10_000L) = apply {
            processMonitors.add(ProcessMonitor(processNames, checkIntervalMs))
        }
        fun build() = GeneralModule(diskMonitors, processMonitors)
    }

    companion object {
        fun builder(context: Context) = Builder(context)
    }
}
