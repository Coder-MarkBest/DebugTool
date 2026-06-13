package com.debugtools.perfmon

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.perfmon.data.ProcessTarget
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

class PerfMonitorModule private constructor(
    private val config: Config,
    private val targets: List<ProcessTarget>
) : DebugModule {

    override val moduleId: String = "debugtools_perfmon"
    override val tabTitle: String = "性能监控"

    private val repository = PerfRepository(config)
    private var presenter: PerfPresenter? = null
    private var tier1: Tier1Sampler? = null
    private var tier2: Tier2Sampler? = null
    private var ioScope: CoroutineScope? = null
    private var mainScope: CoroutineScope? = null
    private var rootView: PerfRootView? = null

    override fun buildSettings(): List<SettingGroup> = emptyList()

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
    }
}
