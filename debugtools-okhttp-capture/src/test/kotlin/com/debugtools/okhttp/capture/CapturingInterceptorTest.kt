package com.debugtools.okhttp.capture

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CapturingInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: NetworkRepository
    private lateinit var client: OkHttpClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        repo = NetworkRepository(Config())
        client = OkHttpClient.Builder().addInterceptor(CapturingInterceptor(repo)).build()
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `captures GET request with response`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val resp = client.newCall(Request.Builder().url(server.url("/api")).build()).execute()
        assertEquals(200, resp.code)
        // Important: business code must still be able to read body — interceptor must NOT consume it
        assertEquals("""{"ok":true}""", resp.body!!.string())

        val record = repo.snapshot().httpRecords.single()
        assertEquals("GET", record.method)
        assertTrue(record.url.endsWith("/api"))
        assertEquals(200, record.responseCode)
        assertEquals("""{"ok":true}""", String(record.responseBody!!))
        assertFalse(record.responseBodyTruncated)
    }

    @Test fun `captures POST request body`() {
        server.enqueue(MockResponse().setResponseCode(201))
        val reqBody = """{"foo":"bar"}""".toRequestBody()
        val resp = client.newCall(
            Request.Builder().url(server.url("/post")).post(reqBody).build()
        ).execute()
        resp.close()

        val record = repo.snapshot().httpRecords.single()
        assertEquals("POST", record.method)
        assertEquals("""{"foo":"bar"}""", String(record.requestBody!!))
    }

    @Test fun `truncates oversized response body`() {
        val repo = NetworkRepository(Config(maxBodyBytes = 10))
        val client = OkHttpClient.Builder().addInterceptor(CapturingInterceptor(repo, Config(maxBodyBytes = 10))).build()
        server.enqueue(MockResponse().setBody("0123456789ABCDEFGHIJ"))  // 20 bytes
        client.newCall(Request.Builder().url(server.url("/big")).build()).execute().close()

        val record = repo.snapshot().httpRecords.single()
        assertEquals(10, record.responseBody!!.size)
        assertTrue(record.responseBodyTruncated)
    }

    @Test fun `records failure when network fails`() {
        val unavailableUrl = server.url("/").toString()
        server.shutdown()
        try {
            client.newCall(Request.Builder().url(unavailableUrl).build()).execute()
            fail("expected IOException")
        } catch (_: Exception) {
            // expected
        }
        val record = repo.snapshot().httpRecords.single()
        assertNotNull(record.failure)
        assertEquals(0, record.responseCode)
    }
}
