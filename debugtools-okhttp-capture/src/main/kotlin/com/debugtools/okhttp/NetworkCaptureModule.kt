package com.debugtools.okhttp

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.overview.OverviewItem
import com.debugtools.core.overview.OverviewMetric
import com.debugtools.core.overview.OverviewProvider
import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.ModuleRecordingSnapshot
import com.debugtools.core.recording.RecordableModule
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.settings.SettingGroup
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.data.WebSocketFrame
import com.debugtools.okhttp.data.WebSocketSession
import com.debugtools.okhttp.capture.CapturingInterceptor
import com.debugtools.okhttp.capture.CapturingListener
import com.debugtools.okhttp.capture.CapturingWebSocket
import com.debugtools.okhttp.capture.TimingEventListener
import com.debugtools.okhttp.presenter.NetworkCapturePresenter
import com.debugtools.okhttp.repository.NetworkRepository
import com.debugtools.okhttp.view.NetworkCaptureRootView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Public entry point for the OkHttp capture module.
 *
 * Usage:
 * ```kotlin
 * val capture = NetworkCaptureModule.create()
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(capture.httpInterceptor())
 *     .eventListenerFactory(capture.eventListenerFactory())  // optional
 *     .build()
 * val ws = capture.newWebSocket(client, request, listener)
 *
 * DebugTools.builder(context).register(capture).build()
 * ```
 */
class NetworkCaptureModule private constructor(
    private val config: Config
) : DebugModule, RecordableModule, OverviewProvider {

    override val moduleId: String = MODULE_ID
    override val recorderId: String = moduleId
    override val tabTitle: String = "网络抓包"

    private val repository = NetworkRepository(config)
    // Shared by the interceptor and the event listener so per-phase timing attaches
    // to the exact captured record (instead of a racy URL match).
    private val correlator = com.debugtools.okhttp.capture.CallTimingCorrelator()
    private var presenter: NetworkCapturePresenter? = null
    private var scope: CoroutineScope? = null
    private var rootView: NetworkCaptureRootView? = null

    fun httpInterceptor(): Interceptor = CapturingInterceptor(repository, config, correlator)

    fun eventListenerFactory(): EventListener.Factory = TimingEventListener.Factory(repository, correlator)

    fun newWebSocket(
        client: OkHttpClient,
        request: Request,
        listener: WebSocketListener
    ): WebSocket {
        val sessionId = UUID.randomUUID().toString()
        // Open the session immediately so the listener has somewhere to record into,
        // before the WebSocket handshake's onOpen callback fires.
        repository.openSession(
            sessionId = sessionId,
            url = request.url.toString(),
            handshakeRecordId = "",
            openedAt = System.currentTimeMillis()
        )
        val capturingListener = CapturingListener(listener, repository, sessionId)
        val raw = client.newWebSocket(request, capturingListener)
        return CapturingWebSocket(raw, repository, sessionId)
    }

    override fun buildSettings(): List<SettingGroup> = emptyList()

    override fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot {
        val snap = repository.snapshot()
        return ModuleRecordingSnapshot(
            moduleId = moduleId,
            summary = mapOf(
                "httpRecords" to snap.httpRecords.size.toString(),
                "webSocketSessions" to snap.webSocketSessions.size.toString()
            )
        )
    }

    override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
        val snap = repository.snapshot()
        val dir = File(context.rootDir, moduleId).apply { mkdirs() }
        val file = File(dir, "network-summary.json")
        file.writeText(JSONObject().apply {
            put("httpRecords", JSONArray().apply { snap.httpRecords.forEach { put(it.toJson()) } })
            put("webSocketSessions", JSONArray().apply { snap.webSocketSessions.forEach { put(it.toJson()) } })
        }.toString(2))
        val errors = snap.httpRecords.count { it.failure != null || it.responseCode >= 400 } +
            snap.webSocketSessions.count { it.failure != null }
        return ModuleRecordingResult(
            moduleId = moduleId,
            files = listOf(file),
            summary = mapOf(
                "httpRecords" to snap.httpRecords.size.toString(),
                "webSocketSessions" to snap.webSocketSessions.size.toString(),
                "errors" to errors.toString()
            )
        )
    }

    override fun createContentView(context: Context): View {
        val view = NetworkCaptureRootView(
            context = context,
            config = config,
            repository = repository,
            onToggleSession = { presenter?.toggleSessionExpanded(it) }
        )
        rootView = view
        presenter?.attachView(view)
        return view
    }

    override fun getBriefItems(): List<BriefItem> {
        val snap = repository.snapshot()
        val errCount = snap.httpRecords.count { it.failure != null || it.responseCode >= 400 } +
            snap.webSocketSessions.count { it.failure != null }
        val frames = snap.webSocketSessions.sumOf { it.frames.size }
        return listOf(
            BriefItem(
                text = "HTTP ${snap.httpRecords.size} · WS ${snap.webSocketSessions.size}(${frames}f)" +
                    if (errCount > 0) " · ${errCount}err" else ""
            )
        )
    }

    override fun getOverviewItems(): List<OverviewItem> =
        listOf(overviewItem(repository.snapshot()))

    override fun onAttach(context: Context, storage: SettingsStorage) {
        val s = CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope = s
        presenter = NetworkCapturePresenter(repository, s)
        rootView?.let { presenter?.attachView(it) }
    }

    override fun onDetach() {
        presenter?.detach()
        presenter = null
        scope?.cancel()
        scope = null
    }

    /** Test-only accessor for the underlying repository. */
    internal fun repositoryForTest(): NetworkRepository = repository

    private fun HttpRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("method", method)
        put("url", url)
        put("protocol", protocol)
        put("responseCode", responseCode)
        put("durationMs", durationMs)
        put("failure", failure)
        put("requestBodyBytes", requestBody?.size ?: 0)
        put("requestBodyTruncated", requestBodyTruncated)
        put("responseBodyBytes", responseBody?.size ?: 0)
        put("responseBodyTruncated", responseBodyTruncated)
        put("isWebSocketUpgrade", isWebSocketUpgrade)
        put("webSocketSessionId", webSocketSessionId)
        timing?.let {
            put("timing", JSONObject().apply {
                put("dnsMs", it.dnsMs)
                put("connectMs", it.connectMs)
                put("tlsMs", it.tlsMs)
                put("requestSendMs", it.requestSendMs)
                put("waitMs", it.waitMs)
                put("responseReceiveMs", it.responseReceiveMs)
                put("totalMs", it.totalMs)
            })
        }
    }

    private fun WebSocketSession.toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("url", url)
        put("handshakeRecordId", handshakeRecordId)
        put("openedAt", openedAt)
        put("closedAt", closedAt)
        put("closeCode", closeCode)
        put("closeReason", closeReason)
        put("failure", failure)
        put("frames", JSONArray().apply { frames.forEach { put(it.toJson()) } })
    }

    private fun WebSocketFrame.toJson(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("direction", direction.name)
        put("type", type.name)
        put("size", size)
        put("storedBytes", payload?.size ?: 0)
        put("truncated", truncated)
    }

    companion object {
        const val MODULE_ID = "debugtools_okhttp_capture"
        fun create(config: Config = Config()): NetworkCaptureModule = NetworkCaptureModule(config)

        fun overviewItem(snap: NetworkRepository.Snapshot): OverviewItem {
            val errors = snap.httpRecords.count { it.failure != null || it.responseCode >= 400 } +
                snap.webSocketSessions.count { it.failure != null }
            val frames = snap.webSocketSessions.sumOf { it.frames.size }
            val status = if (errors > 0) OverviewStatus.ERROR else OverviewStatus.OK
            return OverviewItem(
                moduleId = MODULE_ID,
                title = "网络抓包",
                status = status,
                primaryText = "HTTP ${snap.httpRecords.size} · WS ${snap.webSocketSessions.size}(${frames}帧)" +
                    if (errors > 0) " · ${errors}错误" else "",
                metrics = listOf(
                    OverviewMetric("HTTP", snap.httpRecords.size.toString()),
                    OverviewMetric("WS", snap.webSocketSessions.size.toString()),
                    OverviewMetric("错误", errors.toString(), if (errors > 0) OverviewStatus.ERROR else OverviewStatus.OK)
                )
            )
        }
    }
}
