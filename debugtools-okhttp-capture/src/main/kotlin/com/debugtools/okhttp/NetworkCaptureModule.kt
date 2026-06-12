package com.debugtools.okhttp

import android.content.Context
import android.view.View
import android.widget.TextView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.okhttp.capture.CapturingInterceptor
import com.debugtools.okhttp.capture.CapturingListener
import com.debugtools.okhttp.capture.CapturingWebSocket
import com.debugtools.okhttp.capture.TimingEventListener
import com.debugtools.okhttp.repository.NetworkRepository
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
) : DebugModule {

    override val moduleId: String = MODULE_ID
    override val tabTitle: String = "网络抓包"

    private val repository = NetworkRepository(config)

    fun httpInterceptor(): Interceptor = CapturingInterceptor(repository, config)

    fun eventListenerFactory(): EventListener.Factory = TimingEventListener.Factory(repository)

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

    override fun createContentView(context: Context): View {
        // V1 placeholder; full UI added in later tasks
        return TextView(context).apply {
            text = "Network Capture: ${repository.snapshot().httpRecords.size} HTTP, " +
                "${repository.snapshot().webSocketSessions.size} WS"
        }
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

    override fun onAttach(context: Context, storage: SettingsStorage) {
        // Presenter wiring added in Task 9/15
    }

    override fun onDetach() {
        // Presenter cleanup added in Task 9/15
    }

    /** Test-only accessor for the underlying repository. */
    internal fun repositoryForTest(): NetworkRepository = repository

    companion object {
        const val MODULE_ID = "debugtools_okhttp_capture"
        fun create(config: Config = Config()): NetworkCaptureModule = NetworkCaptureModule(config)
    }
}
