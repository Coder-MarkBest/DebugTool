package com.debugtools.okhttp.capture

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TimingEventListenerTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: NetworkRepository
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        repo = NetworkRepository(Config())
        val correlator = CallTimingCorrelator()
        client = OkHttpClient.Builder()
            .addInterceptor(CapturingInterceptor(repo, correlator = correlator))
            .eventListenerFactory(TimingEventListener.Factory(repo, correlator))
            .build()
    }

    @After fun tearDown() { try { server.shutdown() } catch (_: Exception) {} }

    @Test fun `attaches timing data to HttpRecord`() {
        server.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()

        val record = repo.snapshot().httpRecords.single()
        assertNotNull("Timing should be attached", record.timing)
        val timing = record.timing!!
        assertNotNull(timing.totalMs)
        assertTrue(timing.totalMs >= 0)
    }

    @Test fun `multiple sequential calls track timings independently`() {
        server.enqueue(MockResponse().setBody("1"))
        server.enqueue(MockResponse().setBody("2"))
        client.newCall(Request.Builder().url(server.url("/a")).build()).execute().close()
        client.newCall(Request.Builder().url(server.url("/b")).build()).execute().close()
        val records = repo.snapshot().httpRecords
        assertEquals(2, records.size)
        records.forEach { assertNotNull(it.timing) }
    }

    @Test fun `failed request still gets timing attached`() {
        val url = server.url("/dead")
        server.shutdown() // connecting now fails -> IOException
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
        } catch (_: Exception) { /* expected */ }
        val record = repo.snapshot().httpRecords.single()
        assertNotNull("failure should be captured", record.failure)
        assertNotNull("a failed request should still carry phase timing", record.timing)
    }
}
