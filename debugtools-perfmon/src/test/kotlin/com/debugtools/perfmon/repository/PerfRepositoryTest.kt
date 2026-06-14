package com.debugtools.perfmon.repository

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PerfRepositoryTest {

    private fun sample(target: ProcessTarget, ts: Long, cpu: Float = 0f, alive: Boolean = true) =
        ProcessSample(
            target = target, pid = if (alive) 100 else null, timestamp = ts,
            cpuPercent = cpu, rssBytes = 0L, threadCount = 0, alive = alive
        )

    @Test fun `addSample appends to that target's series`() {
        val repo = PerfRepository(Config())
        val t = ProcessTarget.ByName("a")
        repo.addSample(sample(t, ts = 1L))
        repo.addSample(sample(t, ts = 2L))
        val snap = repo.state.value.series[t.key]!!.snapshot()
        assertEquals(2, snap.size)
    }

    @Test fun `series is independent per target`() {
        val repo = PerfRepository(Config())
        val a = ProcessTarget.ByName("a")
        val b = ProcessTarget.ByName("b")
        repo.addSample(sample(a, ts = 1L, cpu = 10f))
        repo.addSample(sample(b, ts = 1L, cpu = 99f))
        assertEquals(10f, repo.state.value.series[a.key]!!.snapshot().last().value.cpuPercent)
        assertEquals(99f, repo.state.value.series[b.key]!!.snapshot().last().value.cpuPercent)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `state re-emits when same target gets new sample`() = runTest(UnconfinedTestDispatcher()) {
        val repo = PerfRepository(Config())
        val t = ProcessTarget.ByName("a")
        val emissions = mutableListOf<PerfRepository.Snapshot>()
        val job = launch { repo.state.toList(emissions) }

        repo.addSample(sample(t, ts = 1L, cpu = 10f))
        repo.addSample(sample(t, ts = 2L, cpu = 20f))
        repo.addSample(sample(t, ts = 3L, cpu = 30f))

        // initial empty + 3 additions = 4 distinct snapshots
        assertEquals(4, emissions.size)
        assertEquals(0L, emissions[0].tick)
        assertEquals(listOf(1L, 2L, 3L), emissions.drop(1).map { it.tick })

        job.cancel()
    }

    @Test fun `setDetail and clearDetail update detail flow`() {
        val repo = PerfRepository(Config())
        assertNull(repo.state.value.detail)
        repo.setDetail(
            com.debugtools.perfmon.data.ProcessDetail(
                pid = 100, timestamp = 1L,
                totalPssKb = 1, dalvikPssKb = 1, nativePssKb = 1, otherPssKb = 1,
                threads = emptyList(), threadStateDistribution = emptyMap()
            )
        )
        assertEquals(100, repo.state.value.detail!!.pid)
        repo.clearDetail()
        assertNull(repo.state.value.detail)
    }
}
