package com.debugtools.perfmon.presenter

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessSample
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.data.TimedValue
import com.debugtools.perfmon.data.ProcessDetail
import com.debugtools.perfmon.repository.PerfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerfPresenterTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeView : PerfView {
        var listUpdates = 0
        var lastRows: List<ProcessRow> = emptyList()
        var lastDetail: ProcessDetail? = null
        var lastSeries: List<TimedValue<ProcessSample>> = emptyList()
        override fun showList(rows: List<ProcessRow>) { listUpdates++; lastRows = rows }
        override fun showDetail(detail: ProcessDetail?, cpuSeries: List<TimedValue<ProcessSample>>) {
            lastDetail = detail; lastSeries = cpuSeries
        }
    }

    private fun sample(target: ProcessTarget, cpu: Float = 0f, pid: Int? = 100) =
        ProcessSample(
            target = target, pid = pid, timestamp = 1L,
            cpuPercent = cpu, rssBytes = 0L, threadCount = 0, alive = pid != null
        )

    @Test fun `showList builds rows from latest sample per target`() = runTest(dispatcher) {
        val repo = PerfRepository(Config())
        val view = FakeView()
        var selectedPid: Int? = null
        val presenter = PerfPresenter(
            repository = repo,
            scope = this,
            sampleMs = 0L,
            onSelectPid = { selectedPid = it }
        )
        presenter.attachView(view)
        repo.addSample(sample(ProcessTarget.ByName("a"), cpu = 12f))
        repo.addSample(sample(ProcessTarget.ByName("b"), cpu = 99f, pid = null))
        advanceTimeBy(50L)
        assertEquals(2, view.lastRows.size)
        val byName = view.lastRows.associateBy { it.displayName }
        assertEquals(12f, byName["a"]!!.cpuPercent, 0.01f)
        assertFalse(byName["b"]!!.alive)
        presenter.detach()
    }

    @Test fun `selectTarget calls onSelectPid and marks row selected`() = runTest(dispatcher) {
        val repo = PerfRepository(Config())
        val view = FakeView()
        var selectedPid: Int? = null
        val presenter = PerfPresenter(repo, this, sampleMs = 0L, onSelectPid = { selectedPid = it })
        presenter.attachView(view)
        repo.addSample(sample(ProcessTarget.ByName("a"), cpu = 12f, pid = 100))
        advanceTimeBy(50L)
        presenter.selectTarget("name:a")
        advanceTimeBy(50L)
        assertEquals(100, selectedPid)
        assertTrue(view.lastRows.single { it.targetKey == "name:a" }.selected)
        presenter.detach()
    }

    @Test fun `detach stops emissions`() = runTest(dispatcher) {
        val repo = PerfRepository(Config())
        val view = FakeView()
        val presenter = PerfPresenter(repo, this, sampleMs = 0L, onSelectPid = {})
        presenter.attachView(view)
        repo.addSample(sample(ProcessTarget.ByName("a")))
        advanceTimeBy(50L)
        val before = view.listUpdates
        presenter.detach()
        repo.addSample(sample(ProcessTarget.ByName("b")))
        advanceTimeBy(50L)
        assertEquals(before, view.listUpdates)
    }
}
