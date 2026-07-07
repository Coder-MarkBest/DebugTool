package com.debugtools.perfmon

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.overview.OverviewItem
import com.debugtools.core.overview.OverviewMetric
import com.debugtools.core.overview.OverviewProvider
import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.ModuleRecordingSnapshot
import com.debugtools.core.recording.RecordableModule
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.recording.RecordingIssue
import com.debugtools.core.recording.RecordingIssueSeverity
import com.debugtools.core.settings.SettingGroup
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.data.ThreadInfo
import com.debugtools.perfmon.presenter.PerfPresenter
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.sampler.Tier1Sampler
import com.debugtools.perfmon.sampler.Tier2Sampler
import com.debugtools.perfmon.source.MemInfoReader
import com.debugtools.perfmon.source.ProcDiscoverer
import com.debugtools.perfmon.source.ProcStatReader
import com.debugtools.perfmon.source.ProcStatmReader
import com.debugtools.perfmon.source.ThreadReader
import com.debugtools.perfmon.view.PerfRootView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class PerfMonitorModule private constructor(
    private val config: Config,
    private val targets: List<ProcessTarget>
) : DebugModule, RecordableModule, OverviewProvider {

    override val moduleId: String = "debugtools_perfmon"
    override val recorderId: String = moduleId
    override val tabTitle: String = "性能监控"

    private val repository = PerfRepository(config)
    private var presenter: PerfPresenter? = null
    private var tier1: Tier1Sampler? = null
    private var tier2: Tier2Sampler? = null
    private var ioScope: CoroutineScope? = null
    private var mainScope: CoroutineScope? = null
    private var rootView: PerfRootView? = null

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot {
        val snap = repository.state.value
        return ModuleRecordingSnapshot(
            moduleId = moduleId,
            summary = mapOf(
                "targets" to snap.series.size.toString(),
                "samples" to snap.series.values.sumOf { it.snapshot().size }.toString()
            )
        )
    }

    override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
        val snap = repository.state.value
        val dir = File(context.rootDir, moduleId).apply { mkdirs() }
        val file = File(dir, "perf-series.json")
        file.writeText(JSONObject().apply {
            put("series", JSONObject().apply {
                snap.series.forEach { (key, series) ->
                    put(key, JSONArray().apply {
                        series.snapshot().forEach { put(it.value.toJson()) }
                    })
                }
            })
            put("detail", snap.detail?.toJson())
        }.toString(2))
        val samples = snap.series.values.sumOf { it.snapshot().size }
        val last = snap.series.values.mapNotNull { it.snapshot().lastOrNull()?.value }
        val highCpu = last.count { it.cpuPercent >= config.cpuRedThreshold }
        return ModuleRecordingResult(
            moduleId = moduleId,
            files = listOf(file),
            issues = performanceIssues(last, config),
            summary = mapOf(
                "targets" to snap.series.size.toString(),
                "samples" to samples.toString(),
                "redCpuTargets" to highCpu.toString()
            )
        )
    }

    override fun createContentView(context: Context): View {
        val view = PerfRootView(
            context = context,
            config = config,
            onSelect = { presenter?.selectTarget(it) }
        )
        rootView = view
        presenter?.attachView(view)
        return view
    }

    override fun getBriefItems(): List<BriefItem> {
        val snap = repository.state.value
        val last = snap.series.values.mapNotNull { it.snapshot().lastOrNull()?.value }
        val anyRed = last.any { it.cpuPercent >= config.cpuRedThreshold }
        return listOf(
            BriefItem(
                text = "${last.size}P · CPU max %.0f%%".format(last.maxOfOrNull { it.cpuPercent } ?: 0f),
                color = if (anyRed) android.graphics.Color.parseColor("#FC8181") else null
            )
        )
    }

    override fun getOverviewItems(): List<OverviewItem> =
        listOf(overviewItem(repository.state.value, config))

    override fun onAttach(context: Context, storage: SettingsStorage) {
        val app = context.applicationContext
        val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val main = CoroutineScope(Dispatchers.Main + SupervisorJob())
        ioScope = io
        mainScope = main

        val procRoot = File("/proc")
        val tier1Sampler = Tier1Sampler(
            targets = targets,
            repository = repository,
            config = config,
            discoverer = ProcDiscoverer(procRoot),
            statReader = ProcStatReader(procRoot),
            statmReader = ProcStatmReader(procRoot),
            threadReader = ThreadReader(procRoot)
        )
        val tier2Sampler = Tier2Sampler(
            repository = repository,
            config = config,
            memReader = MemInfoReader(app),
            threadReader = ThreadReader(procRoot)
        )
        tier1 = tier1Sampler.also { it.start(io) }
        tier2 = tier2Sampler.also { it.start(io) }

        val p = PerfPresenter(
            repository = repository,
            scope = main,
            onSelectPid = { pid -> tier2?.selectPid(pid) }
        )
        presenter = p
        rootView?.let { p.attachView(it) }
    }

    override fun onDetach() {
        presenter?.detach()
        presenter = null
        tier1?.stop()
        tier2?.stop()
        ioScope?.cancel()
        mainScope?.cancel()
        ioScope = null
        mainScope = null
    }

    internal fun targetsForTest(): List<ProcessTarget> = targets
    internal fun configForTest(): Config = config
    internal fun repositoryForTest(): PerfRepository = repository

    private fun ProcessSample.toJson(): JSONObject = JSONObject().apply {
        put("target", target.key)
        put("pid", pid)
        put("timestamp", timestamp)
        put("cpuPercent", cpuPercent.toDouble())
        put("rssBytes", rssBytes)
        put("threadCount", threadCount)
        put("alive", alive)
    }

    private fun ProcessDetail.toJson(): JSONObject = JSONObject().apply {
        put("pid", pid)
        put("timestamp", timestamp)
        put("totalPssKb", totalPssKb)
        put("dalvikPssKb", dalvikPssKb)
        put("nativePssKb", nativePssKb)
        put("otherPssKb", otherPssKb)
        put("threads", JSONArray().apply { threads.forEach { put(it.toJson()) } })
        put("threadStateDistribution", JSONObject().apply {
            threadStateDistribution.forEach { (state, count) -> put(state.name, count) }
        })
    }

    private fun ThreadInfo.toJson(): JSONObject = JSONObject().apply {
        put("tid", tid)
        put("name", name)
        put("cpuPercent", cpuPercent.toDouble())
        put("state", state.name)
    }

    private fun performanceIssues(last: List<ProcessSample>, config: Config): List<RecordingIssue> {
        val deadIssues = last.filter { !it.alive }.map { sample ->
            RecordingIssue(
                severity = RecordingIssueSeverity.CRITICAL,
                type = "PROCESS_UNAVAILABLE",
                detail = "${sample.target.label()} is not alive",
                moduleId = moduleId,
                evidence = "target=${sample.target.key}, pid=${sample.pid ?: "none"}, timestamp=${sample.timestamp}",
                suggestion = "Check whether the monitored process crashed, was killed, or was not started."
            )
        }
        val cpuIssues = last
            .filter { it.alive && it.cpuPercent >= config.cpuRedThreshold }
            .sortedByDescending { it.cpuPercent }
            .take(3)
            .map { sample ->
                RecordingIssue(
                    severity = RecordingIssueSeverity.CRITICAL,
                    type = "HIGH_CPU",
                    detail = "${sample.target.label()} CPU is above red threshold",
                    moduleId = moduleId,
                    evidence = "%.0f%% >= %d%%, pid=%s, threads=%d".format(
                        sample.cpuPercent,
                        config.cpuRedThreshold,
                        sample.pid?.toString() ?: "none",
                        sample.threadCount
                    ),
                    suggestion = "Open the performance tab or perf-series.json and inspect thread-level samples for this process."
                )
            }
        return deadIssues + cpuIssues
    }

    private fun ProcessTarget.label(): String = when (this) {
        is ProcessTarget.ByName -> processName
        is ProcessTarget.ByPid -> "pid:$pid"
    }

    class Builder {
        private val targets = mutableListOf<ProcessTarget>()
        private var config = Config()
        fun addProcessByName(processName: String) = apply {
            targets += ProcessTarget.ByName(processName)
        }
        fun addProcessByPid(pid: Int) = apply {
            targets += ProcessTarget.ByPid(pid)
        }
        fun updateIntervalSec(sec: Int) = apply {
            config = config.copy(updateIntervalSec = sec.coerceIn(5, 60))
        }
        fun windowMin(min: Int) = apply {
            config = config.copy(windowMin = min.coerceIn(5, 120))
        }
        fun cpuThresholdPercent(orange: Int = 50, red: Int = 80) = apply {
            config = config.copy(cpuOrangeThreshold = orange, cpuRedThreshold = red)
        }
        fun pssThresholdMb(red: Int) = apply {
            config = config.copy(pssRedThresholdMb = red)
        }
        fun topThreadCount(n: Int) = apply {
            config = config.copy(topThreadCount = n.coerceIn(3, 50))
        }
        fun build() = PerfMonitorModule(config, targets.toList())
    }

    companion object {
        fun builder() = Builder()

        fun overviewItem(snap: PerfRepository.Snapshot, config: Config): OverviewItem {
            val last = snap.series.values.mapNotNull { it.snapshot().lastOrNull()?.value }
            if (last.isEmpty()) {
                return OverviewItem(
                    moduleId = "debugtools_perfmon",
                    title = "性能监控",
                    status = OverviewStatus.UNKNOWN,
                    primaryText = "暂无性能样本"
                )
            }
            val maxCpu = last.maxOf { it.cpuPercent }
            val dead = last.count { !it.alive }
            val redCpu = last.count { it.cpuPercent >= config.cpuRedThreshold }
            val orangeCpu = last.count { it.cpuPercent >= config.cpuOrangeThreshold }
            val status = when {
                dead > 0 || redCpu > 0 -> OverviewStatus.ERROR
                orangeCpu > 0 -> OverviewStatus.WARNING
                else -> OverviewStatus.OK
            }
            return OverviewItem(
                moduleId = "debugtools_perfmon",
                title = "性能监控",
                status = status,
                primaryText = "${last.size}进程 · CPU max %.0f%%".format(maxCpu) +
                    if (dead > 0) " · ${dead}不可用" else "",
                metrics = listOf(
                    OverviewMetric("进程", last.size.toString()),
                    OverviewMetric("CPU max", "%.0f%%".format(maxCpu), if (redCpu > 0) OverviewStatus.ERROR else if (orangeCpu > 0) OverviewStatus.WARNING else OverviewStatus.OK),
                    OverviewMetric("不可用", dead.toString(), if (dead > 0) OverviewStatus.ERROR else OverviewStatus.OK)
                )
            )
        }
    }
}
