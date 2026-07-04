package com.debugtools.general

import android.content.Context
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.overview.OverviewItem
import com.debugtools.core.overview.OverviewMetric
import com.debugtools.core.overview.OverviewProvider
import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AvailabilityModule private constructor(
    private val processMonitors: List<ProcessMonitor>,
    private val networkCheck: NetworkCheck?,
    private val externalSources: List<AvailabilityItemSource>
) : DebugModule, OverviewProvider {
    override val moduleId = MODULE_ID
    override val tabTitle = TITLE

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processItems = linkedMapOf<String, AvailabilityItem>()
    private var networkItem: AvailabilityItem? = null
    private var contentRoot: LinearLayout? = null

    override fun buildSettings() = emptyList<SettingGroup>()

    override fun createContentView(context: Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }.also { root ->
            contentRoot = root
            rebuildView()
        }
    }

    override fun getBriefItems(): List<BriefItem> {
        val problem = currentItems()
            .filter { it.status != AvailabilityStatus.AVAILABLE }
            .maxByOrNull { it.status.severity }
        return if (problem == null) {
            listOf(BriefItem("可用", Color.parseColor("#68D391")))
        } else {
            listOf(BriefItem("${problem.title}: ${problem.status.label()}", problem.status.color()))
        }
    }

    override fun getOverviewItems(): List<OverviewItem> =
        listOf(overviewItem(currentItems()))

    override fun onAttach(context: Context, storage: SettingsStorage) {
        processMonitors.forEach { monitor ->
            monitor.start(context, scope)
            scope.launch {
                monitor.statesFlow.collect { states ->
                    withContext(Dispatchers.Main) {
                        states.forEach { (name, alive) ->
                            processItems["process:$name"] = AvailabilityItem(
                                id = "process:$name",
                                title = name.substringAfterLast('.'),
                                status = if (alive) AvailabilityStatus.AVAILABLE else AvailabilityStatus.UNAVAILABLE,
                                message = if (alive) "进程运行中" else "进程未运行"
                            )
                        }
                        rebuildView()
                    }
                }
            }
        }
        networkCheck?.let { check ->
            scope.launch {
                while (isActive) {
                    val item = check.toItem(context)
                    withContext(Dispatchers.Main) {
                        networkItem = item
                        rebuildView()
                    }
                    delay(check.intervalMs)
                }
            }
        }
    }

    override fun onDetach() {
        scope.cancel()
    }

    private fun currentItems(): List<AvailabilityItem> {
        return buildList {
            networkItem?.let(::add)
            addAll(processItems.values)
            externalSources.forEach { source ->
                addAll(runCatching { source.getAvailabilityItems() }.getOrElse { emptyList() })
            }
        }
    }

    private fun rebuildView() {
        val root = contentRoot ?: return
        root.removeAllViews()
        val items = currentItems()
        if (items.isEmpty()) {
            root.addView(TextView(root.context).apply {
                text = "暂无可用性数据"
                setTextColor(Color.parseColor("#A0AEC0"))
                textSize = 14f
            })
            return
        }
        items.sortedByDescending { it.status.severity }.forEach { item ->
            root.addView(TextView(root.context).apply {
                text = buildString {
                    append(item.title)
                    append("  ")
                    append(item.status.label())
                    if (item.message.isNotBlank()) {
                        append("\n")
                        append(item.message)
                    }
                }
                setTextColor(item.status.color())
                textSize = 14f
                setPadding(0, 8, 0, 8)
            })
        }
    }

    data class NetworkCheck(
        val id: String = "network",
        val title: String = "网络",
        val intervalMs: Long = 5_000L
    ) {
        fun toItem(context: Context): AvailabilityItem {
            val available = runCatching {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            }.getOrDefault(false)
            return AvailabilityItem(
                id = id,
                title = title,
                status = if (available) AvailabilityStatus.AVAILABLE else AvailabilityStatus.UNAVAILABLE,
                message = if (available) "网络可用" else "网络不可用"
            )
        }
    }

    class Builder {
        private val processMonitors = mutableListOf<ProcessMonitor>()
        private var networkCheck: NetworkCheck? = null
        private val externalSources = mutableListOf<AvailabilityItemSource>()

        fun addProcessCheck(processNames: List<String>, checkIntervalMs: Long = 10_000L) = apply {
            processMonitors.add(ProcessMonitor(processNames, checkIntervalMs))
        }

        fun addNetworkCheck(intervalMs: Long = 5_000L, title: String = "网络") = apply {
            networkCheck = NetworkCheck(title = title, intervalMs = intervalMs)
        }

        fun addExternalSource(source: AvailabilityItemSource) = apply {
            externalSources.add(source)
        }

        fun build() = AvailabilityModule(processMonitors, networkCheck, externalSources)
    }

    companion object {
        const val MODULE_ID = "debugtools_availability"
        const val TITLE = "可用性"

        fun builder() = Builder()

        fun builder(@Suppress("UNUSED_PARAMETER") context: Context) = Builder()

        fun overviewItem(items: List<AvailabilityItem>): OverviewItem {
            if (items.isEmpty()) {
                return OverviewItem(
                    moduleId = MODULE_ID,
                    title = TITLE,
                    status = OverviewStatus.UNKNOWN,
                    primaryText = "暂无可用性数据"
                )
            }
            val unavailable = items.count { it.status == AvailabilityStatus.UNAVAILABLE }
            val degraded = items.count { it.status == AvailabilityStatus.DEGRADED }
            val unknown = items.count { it.status == AvailabilityStatus.UNKNOWN }
            val overviewStatus = when {
                unavailable > 0 -> OverviewStatus.ERROR
                degraded > 0 -> OverviewStatus.WARNING
                unknown > 0 -> OverviewStatus.UNKNOWN
                else -> OverviewStatus.OK
            }
            val primary = "${unavailable}不可用 · ${degraded}降级 · ${unknown}未知"
            val metrics = items
                .filter { it.status != AvailabilityStatus.AVAILABLE }
                .sortedByDescending { it.status.severity }
                .take(4)
                .map { item ->
                    OverviewMetric(
                        label = item.title,
                        value = item.message.ifBlank { item.status.label() },
                        status = item.status.toOverviewStatus()
                    )
                }
            return OverviewItem(
                moduleId = MODULE_ID,
                title = TITLE,
                status = overviewStatus,
                primaryText = primary,
                metrics = metrics
            )
        }
    }
}

private fun AvailabilityStatus.toOverviewStatus(): OverviewStatus = when (this) {
    AvailabilityStatus.AVAILABLE -> OverviewStatus.OK
    AvailabilityStatus.DEGRADED -> OverviewStatus.WARNING
    AvailabilityStatus.UNAVAILABLE -> OverviewStatus.ERROR
    AvailabilityStatus.UNKNOWN -> OverviewStatus.UNKNOWN
}

private fun AvailabilityStatus.label(): String = when (this) {
    AvailabilityStatus.AVAILABLE -> "可用"
    AvailabilityStatus.DEGRADED -> "降级"
    AvailabilityStatus.UNAVAILABLE -> "不可用"
    AvailabilityStatus.UNKNOWN -> "未知"
}

private fun AvailabilityStatus.color(): Int = when (this) {
    AvailabilityStatus.AVAILABLE -> Color.parseColor("#68D391")
    AvailabilityStatus.DEGRADED -> Color.parseColor("#F6AD55")
    AvailabilityStatus.UNAVAILABLE -> Color.parseColor("#FC8181")
    AvailabilityStatus.UNKNOWN -> Color.parseColor("#A0AEC0")
}
