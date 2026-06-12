package com.debugtools.okhttp.presenter

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.repository.NetworkRepository
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
class NetworkCapturePresenterTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun httpRecord(id: String, ts: Long) = HttpRecord(
        id = id, timestamp = ts, method = "GET", url = "/", protocol = "HTTP/1.1",
        requestHeaders = emptyList(), requestBody = null, requestBodyTruncated = false,
        responseCode = 200, responseHeaders = emptyList(), responseBody = null,
        responseBodyTruncated = false, durationMs = 1L, timing = null
    )

    private class FakeView : NetworkCaptureView {
        val updates = mutableListOf<List<ListItem>>()
        override fun showItems(items: List<ListItem>) { updates += items }
    }

    @Test fun `flatten HTTP and WS interleaved by timestamp ascending`() = runTest(dispatcher) {
        val repo = NetworkRepository(Config())
        val view = FakeView()
        val presenter = NetworkCapturePresenter(repo, this, sampleMs = 0L)
        presenter.attachView(view)

        repo.addHttp(httpRecord("a", ts = 100L))
        repo.openSession("s1", "wss://x", "h", openedAt = 50L)
        repo.addFrame("s1", Direction.SEND, FrameType.TEXT, "1".toByteArray(), timestamp = 60L)
        repo.addHttp(httpRecord("b", ts = 200L))
        advanceTimeBy(50L)

        val last = view.updates.last()
        // Order: WS session (50) → frame (60) → HTTP a (100) → HTTP b (200)
        assertEquals(4, last.size)
        assertTrue(last[0] is ListItem.WebSocketSessionRow)
        assertTrue(last[1] is ListItem.WebSocketFrameRow)
        assertEquals("a", (last[2] as ListItem.HttpRow).record.id)
        assertEquals("b", (last[3] as ListItem.HttpRow).record.id)

        presenter.detach()
    }

    @Test fun `toggleSessionExpanded hides frames when collapsed`() = runTest(dispatcher) {
        val repo = NetworkRepository(Config())
        val view = FakeView()
        val presenter = NetworkCapturePresenter(repo, this, sampleMs = 0L)
        presenter.attachView(view)
        repo.openSession("s1", "wss://x", "h", 0L)
        repo.addFrame("s1", Direction.SEND, FrameType.TEXT, "1".toByteArray(), 1L)
        advanceTimeBy(50L)
        assertEquals(2, view.updates.last().size)  // session + 1 frame

        presenter.toggleSessionExpanded("s1")
        advanceTimeBy(50L)
        assertEquals(1, view.updates.last().size)  // only the session row

        presenter.detach()
    }

    @Test fun `detach stops emissions`() = runTest(dispatcher) {
        val repo = NetworkRepository(Config())
        val view = FakeView()
        val presenter = NetworkCapturePresenter(repo, this, sampleMs = 0L)
        presenter.attachView(view)
        advanceTimeBy(50L)
        val beforeCount = view.updates.size
        presenter.detach()
        repo.addHttp(httpRecord("x", 0L))
        advanceTimeBy(50L)
        assertEquals(beforeCount, view.updates.size)
    }
}
