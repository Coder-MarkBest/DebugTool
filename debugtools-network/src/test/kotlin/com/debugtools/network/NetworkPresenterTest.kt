package com.debugtools.network

import com.debugtools.network.model.NetworkQuality
import com.debugtools.network.model.NetworkType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkPresenterTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `quality is OFFLINE when network type is NONE`() {
        assertEquals(NetworkQuality.OFFLINE, NetworkQuality.from(NetworkType.NONE, null))
    }

    @Test fun `quality is EXCELLENT for wifi under 50ms`() {
        assertEquals(NetworkQuality.EXCELLENT, NetworkQuality.from(NetworkType.WIFI, 30))
    }

    @Test fun `quality is GOOD for wifi between 50 and 150ms`() {
        assertEquals(NetworkQuality.GOOD, NetworkQuality.from(NetworkType.WIFI, 100))
    }

    @Test fun `quality is POOR for wifi over 150ms`() {
        assertEquals(NetworkQuality.POOR, NetworkQuality.from(NetworkType.WIFI, 350))
    }

    @Test fun `presenter delivers state to view`() = runTest {
        val fakeSource = FakeNetworkDataSource()
        val view = FakeNetworkView()
        val presenter = NetworkPresenter(fakeSource, this)
        presenter.attachView(view)
        fakeSource.emit(NetworkType.WIFI, 23)
        advanceUntilIdle()
        assertTrue(view.lastText.contains("WiFi"))
        assertTrue(view.lastText.contains("23ms"))
        presenter.detach()
    }
}

private class FakeNetworkDataSource : NetworkDataSource {
    private val _flow = MutableSharedFlow<Pair<NetworkType, Int?>>()
    override val stateFlow: Flow<Pair<NetworkType, Int?>> = _flow
    suspend fun emit(type: NetworkType, pingMs: Int?) = _flow.emit(Pair(type, pingMs))
}

private class FakeNetworkView : NetworkView {
    var lastText: String = ""
    var lastColor: Int = 0
    override fun showNetworkState(text: String, color: Int) { lastText = text; lastColor = color }
}
