package com.debugtools.general

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeneralPresenterTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `presenter delivers initial disk size to view`() = runTest {
        val fakeMonitor = FakeDiskMonitor("/data", initialSize = 1024L)
        val view = FakeGeneralView()
        val presenter = GeneralPresenter(
            diskMonitors = listOf(fakeMonitor),
            processMonitors = emptyList(),
            scope = this
        )
        presenter.attachView(view)
        advanceUntilIdle()
        assertNotNull(view.lastDiskSizes)
        assertEquals(1, view.lastDiskSizes?.size)
        assertEquals(1024L, view.lastDiskSizes?.firstOrNull()?.second)
    }

    @Test fun `presenter delivers process states to view`() = runTest {
        val fakeProcess = FakeProcessMonitor(listOf("com.example"), initialState = mapOf("com.example" to true))
        val view = FakeGeneralView()
        val presenter = GeneralPresenter(
            diskMonitors = emptyList(),
            processMonitors = listOf(fakeProcess),
            scope = this
        )
        presenter.attachView(view)
        advanceUntilIdle()
        assertNotNull(view.lastProcessStates)
        assertTrue(view.lastProcessStates?.firstOrNull()?.second == true)
    }
}

private class FakeDiskMonitor(path: String, initialSize: Long) : DiskMonitor(path) {
    override val sizeFlow = MutableStateFlow(initialSize)
}

private class FakeProcessMonitor(processNames: List<String>, initialState: Map<String, Boolean>)
    : ProcessMonitor(processNames) {
    override val statesFlow = MutableStateFlow(initialState)
}

private class FakeGeneralView : GeneralView {
    var lastDiskSizes: List<Pair<String, Long>>? = null
    var lastProcessStates: List<Pair<String, Boolean>>? = null
    override fun showDiskSizes(sizes: List<Pair<String, Long>>) { lastDiskSizes = sizes }
    override fun showProcessStates(states: List<Pair<String, Boolean>>) { lastProcessStates = states }
}
