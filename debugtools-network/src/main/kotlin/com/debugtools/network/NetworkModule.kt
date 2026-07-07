package com.debugtools.network

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.overview.OverviewItem
import com.debugtools.core.overview.OverviewProvider
import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.recording.ModuleRecordingResult
import com.debugtools.core.recording.ModuleRecordingSnapshot
import com.debugtools.core.recording.RecordableModule
import com.debugtools.core.recording.RecordingContext
import com.debugtools.core.settings.SettingGroup
import com.debugtools.core.settings.SettingItem
import com.debugtools.okhttp.NetworkCaptureModule
import com.debugtools.okhttp.NetworkCaptureModule.NetworkCaptureSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class NetworkModule private constructor(
    private val defaultGateway: String,
    private val captureModule: NetworkCaptureModule?
) : DebugModule, NetworkView, OverviewProvider, RecordableModule {
    override val moduleId = "debugtools_network"
    override val recorderId = moduleId
    override val tabTitle = "网络"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var presenter: NetworkPresenter? = null
    private var stateText = "检测中..."
    private var stateColor = Color.GRAY
    private var contentView: TextView? = null

    override fun buildSettings() = listOf(
        SettingGroup("网络设置", listOf(
            SettingItem.EditText("gateway", "Ping 网关", default = defaultGateway, hint = "例：8.8.8.8")
        ))
    )

    override fun createContentView(context: Context): View {
        val qualityView = TextView(context).apply {
            text = stateText
            setTextColor(stateColor)
            setPadding(24, 24, 24, 24)
            textSize = 14f
        }.also { contentView = it }
        val capture = captureModule ?: return qualityView
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(qualityView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(TextView(context).apply {
                text = "抓包记录"
                setTextColor(Color.parseColor("#A0AEC0"))
                setBackgroundColor(Color.parseColor("#111827"))
                setPadding(24, 10, 24, 10)
                textSize = 12f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(capture.createContentView(context), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }
    }

    override fun getBriefItems(): List<BriefItem> {
        val capture = captureModule?.captureSummary()
        return listOf(
            BriefItem(
                stateText + if (capture != null) {
                    " · HTTP ${capture.httpCount} · WS ${capture.webSocketCount}" +
                        if (capture.errorCount > 0) " · ${capture.errorCount}错误" else ""
                } else "",
                stateColor
            )
        )
    }

    override fun getOverviewItems(): List<OverviewItem> =
        listOf(overviewItem(stateText, captureModule?.captureSummary()))

    override fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot {
        val snapshot = captureModule?.onRecordingStart(context) ?: return ModuleRecordingSnapshot(
            moduleId = moduleId,
            summary = mapOf("state" to stateText)
        )
        return snapshot.copy(moduleId = moduleId)
    }

    override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
        val result = captureModule?.onRecordingStop(context) ?: return ModuleRecordingResult(
            moduleId = moduleId,
            summary = mapOf("state" to stateText)
        )
        return result.copy(
            moduleId = moduleId,
            issues = result.issues.map { it.copy(moduleId = moduleId) }
        )
    }

    override fun onAttach(context: Context, storage: SettingsStorage) {
        val gateway = storage.getString("gateway", defaultGateway)
        val dataSource = DefaultNetworkDataSource(context, gateway)
        presenter = NetworkPresenter(dataSource, scope).also { it.attachView(this) }
        captureModule?.onAttach(context, storage)
    }

    override fun onDetach() {
        captureModule?.onDetach()
        presenter?.detach()
        scope.cancel()
    }

    override fun showNetworkState(text: String, color: Int) {
        stateText = text
        stateColor = color
        contentView?.apply { this.text = text; setTextColor(color) }
    }

    companion object {
        fun create(gateway: String = "8.8.8.8") = NetworkModule(gateway, captureModule = null)

        fun builder() = Builder()

        fun overviewItem(
            stateText: String,
            captureSummary: NetworkCaptureSummary? = null
        ): OverviewItem {
            val qualityStatus = when {
                stateText.contains("无网络") || stateText.contains("离线") -> OverviewStatus.ERROR
                stateText.contains("差") || stateText.contains("超时") -> OverviewStatus.WARNING
                stateText == "检测中..." -> OverviewStatus.UNKNOWN
                else -> OverviewStatus.OK
            }
            val status = when {
                captureSummary?.errorCount ?: 0 > 0 -> OverviewStatus.ERROR
                else -> qualityStatus
            }
            val captureText = captureSummary?.let {
                " · HTTP ${it.httpCount} · WS ${it.webSocketCount}(${it.webSocketFrameCount}帧)" +
                    if (it.errorCount > 0) " · ${it.errorCount}错误" else ""
            }.orEmpty()
            return OverviewItem(
                moduleId = "debugtools_network",
                title = "网络",
                status = status,
                primaryText = stateText + captureText
            )
        }
    }

    class Builder {
        private var gateway: String = "8.8.8.8"
        private var captureModule: NetworkCaptureModule? = null

        fun gateway(gateway: String) = apply {
            this.gateway = gateway
        }

        fun capture(captureModule: NetworkCaptureModule) = apply {
            this.captureModule = captureModule
        }

        fun build() = NetworkModule(gateway, captureModule)
    }

    internal fun hasCaptureForTest(): Boolean = captureModule != null
}
