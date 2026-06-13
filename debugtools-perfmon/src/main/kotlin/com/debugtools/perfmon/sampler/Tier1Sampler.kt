package com.debugtools.perfmon.sampler

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.ProcDiscoverer
import com.debugtools.perfmon.source.ProcStatReader
import com.debugtools.perfmon.source.ProcStatmReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loops every [Config.updateIntervalSec] seconds and emits one [ProcessSample]
 * per target into the [PerfRepository]. Designed to be extremely cheap — each
 * iteration is a few /proc reads (< 10ms for typical 4-target workloads).
 */
class Tier1Sampler(
    private val targets: List<ProcessTarget>,
    private val repository: PerfRepository,
    private val config: Config,
    private val discoverer: ProcDiscoverer,
    private val statReader: ProcStatReader,
    private val statmReader: ProcStatmReader,
    private val threadReader: ThreadReader
) {
    private var job: Job? = null
    private val pidByTargetKey = HashMap<String, Int?>()

    fun start(scope: CoroutineScope) {
        job = scope.launch {
            while (isActive) {
                sampleOnce()
                delay(config.updateIntervalSec * 1_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun sampleOnce() {
        val now = System.currentTimeMillis()
        for (target in targets) {
            val newPid = discoverer.resolve(target)
            val oldPid = pidByTargetKey[target.key]
            if (oldPid != null && oldPid != newPid) {
                // pid changed — drop baselines so next read primes fresh
                statReader.forget(oldPid)
                threadReader.forget(oldPid)
            }
            pidByTargetKey[target.key] = newPid

            val sample = if (newPid == null) {
                ProcessSample(
                    target = target, pid = null, timestamp = now,
                    cpuPercent = 0f, rssBytes = 0L, threadCount = 0, alive = false
                )
            } else {
                val cpu = statReader.read(newPid) ?: 0f
                val rss = statmReader.readRssBytes(newPid) ?: 0L
                val threads = threadReader.countThreads(newPid)
                ProcessSample(
                    target = target, pid = newPid, timestamp = now,
                    cpuPercent = cpu, rssBytes = rss, threadCount = threads, alive = true
                )
            }
            repository.addSample(sample)
        }
    }
}
