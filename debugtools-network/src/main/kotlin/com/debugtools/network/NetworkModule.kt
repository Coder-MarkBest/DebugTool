package com.debugtools.network

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.core.settings.SettingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class NetworkModule private constructor(
    private val defaultGateway: String
) : DebugModule, NetworkView {
    override val moduleId = "debugtools_network"
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
        return TextView(context).apply {
            text = stateText
            setTextColor(stateColor)
            setPadding(24, 24, 24, 24)
            textSize = 14f
        }.also { contentView = it }
    }

    override fun getBriefItems() = listOf(BriefItem(stateText, stateColor))

    override fun onAttach(context: Context, storage: SettingsStorage) {
        val gateway = storage.getString("gateway", defaultGateway)
        val dataSource = DefaultNetworkDataSource(context, gateway)
        presenter = NetworkPresenter(dataSource, scope).also { it.attachView(this) }
    }

    override fun onDetach() {
        presenter?.detach()
        scope.cancel()
    }

    override fun showNetworkState(text: String, color: Int) {
        stateText = text
        stateColor = color
        contentView?.apply { this.text = text; setTextColor(color) }
    }

    companion object {
        fun create(gateway: String = "8.8.8.8") = NetworkModule(gateway)
    }
}
