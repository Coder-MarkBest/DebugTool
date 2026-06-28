package com.debugtools.okhttp.capture

import com.debugtools.okhttp.Config
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID

/**
 * Captures every HTTP request flowing through the OkHttp [Interceptor] chain.
 *
 * Response body is read with [Response.peekBody] so the original body remains
 * available to the business code. Bodies exceeding [Config.maxBodyBytes] are truncated.
 *
 * WebSocket upgrade requests (Upgrade: websocket header) are flagged via
 * [HttpRecord.isWebSocketUpgrade].
 */
class CapturingInterceptor(
    private val repository: NetworkRepository,
    private val config: Config = Config(),
    private val correlator: CallTimingCorrelator? = null
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Generate the record id up-front and link it to this Call so TimingEventListener
        // can attach phase timing to this exact record (success or failure).
        val recordId = UUID.randomUUID().toString()
        correlator?.link(chain.call(), recordId)
        val startedAtNanos = System.nanoTime()
        val timestamp = System.currentTimeMillis()

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            repository.addHttp(failureRecord(recordId, request, e, timestamp, durationMs))
            throw e
        }

        val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
        repository.addHttp(buildRecord(recordId, request, response, timestamp, durationMs))
        return response
    }

    private fun buildRecord(
        recordId: String,
        request: okhttp3.Request,
        response: Response,
        timestamp: Long,
        durationMs: Long
    ): HttpRecord {
        val reqBody = readRequestBody(request)
        val reqTruncated = (request.body?.contentLength() ?: 0L) > config.maxBodyBytes
        val (respBody, respTruncated) = readResponseBody(response)
        val isUpgrade = response.code == 101 &&
            request.header("Upgrade")?.equals("websocket", ignoreCase = true) == true

        return HttpRecord(
            id = recordId,
            timestamp = timestamp,
            method = request.method,
            url = request.url.toString(),
            protocol = response.protocol.toString().uppercase(),
            requestHeaders = request.headers.map { it.first to it.second },
            requestBody = reqBody,
            requestBodyTruncated = reqTruncated,
            responseCode = response.code,
            responseHeaders = response.headers.map { it.first to it.second },
            responseBody = respBody,
            responseBodyTruncated = respTruncated,
            durationMs = durationMs,
            timing = null,
            failure = null,
            isWebSocketUpgrade = isUpgrade
        )
    }

    private fun failureRecord(
        recordId: String,
        request: okhttp3.Request,
        error: Throwable,
        timestamp: Long,
        durationMs: Long
    ): HttpRecord = HttpRecord(
        id = recordId,
        timestamp = timestamp,
        method = request.method,
        url = request.url.toString(),
        protocol = "UNKNOWN",
        requestHeaders = request.headers.map { it.first to it.second },
        requestBody = readRequestBody(request),
        requestBodyTruncated = false,
        responseCode = 0,
        responseHeaders = emptyList(),
        responseBody = null,
        responseBodyTruncated = false,
        durationMs = durationMs,
        timing = null,
        failure = "${error.javaClass.simpleName}: ${error.message ?: "(no message)"}",
        isWebSocketUpgrade = false
    )

    private fun readRequestBody(request: okhttp3.Request): ByteArray? {
        val body = request.body ?: return null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val raw = buffer.readByteArray()
            if (raw.size > config.maxBodyBytes) raw.copyOfRange(0, config.maxBodyBytes) else raw
        } catch (_: Exception) {
            null
        }
    }

    private fun readResponseBody(response: Response): Pair<ByteArray?, Boolean> {
        val source = response.peekBody(config.maxBodyBytes.toLong() + 1L)
        return try {
            val bytes = source.bytes()
            if (bytes.size > config.maxBodyBytes) {
                bytes.copyOfRange(0, config.maxBodyBytes) to true
            } else {
                bytes to false
            }
        } catch (_: Exception) {
            null to false
        }
    }
}
