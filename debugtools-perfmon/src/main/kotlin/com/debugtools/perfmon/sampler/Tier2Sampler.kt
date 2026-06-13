package com.debugtools.perfmon.sampler

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.data.ThreadState
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.MemInfoReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the currently-selected pid every interval and emits a [ProcessDetail].
 * When [selectPid] is called with a different pid, restarts the loop targeting
 * the new pid. Null pid pauses sampling and clears the detail in the repository.
 */
class Tier2Sampler(
    private val repository: PerfRepository,
    private val config: Config,
    private val memReader: MemInfoReader,
    private val threadReader: ThreadReader
) {
    private var loopJob: Job? = null
    private var scope: CoroutineScope? = null
    @Volatile private var selectedPid: Int? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        scope = null
    }

    fun selectPid(pid: Int?) {
        if (pid == selectedPid) return
        selectedPid = pid
        loopJob?.cancel()
        if (pid == null) {
            repository.clearDetail()
            return
        }
        loopJob = scope?.launch {
            while (isActive && selectedPid == pid) {
                sampleOnce(pid)
                delay(config.updateIntervalSec * 1_000L)
            }
        }
    }

    private fun sampleOnce(pid: Int) {
        val mem = memReader.read(pid)
        val threads = threadReader.readDetailed(pid)
        val top = threads.sortedByDescending { it.cpuPercent }.take(config.topThreadCount)
        val distribution: Map<ThreadState, Int> = threads.groupingBy { it.state }.eachCount()
        repository.setDetail(
            ProcessDetail(
                pid = pid,
                timestamp = System.currentTimeMillis(),
                totalPssKb = mem?.totalPssKb ?: 0,
                dalvikPssKb = mem?.dalvikPssKb ?: 0,
                nativePssKb = mem?.nativePssKb ?: 0,
                otherPssKb = mem?.otherPssKb ?: 0,
                threads = top,
                threadStateDistribution = distribution
            )
        )
    }
}
