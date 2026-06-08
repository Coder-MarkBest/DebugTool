package com.debugtools.network

import com.debugtools.network.model.NetworkQuality
import com.debugtools.network.model.NetworkType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkPresenter(
    private val dataSource: NetworkDataSource,
    private val scope: CoroutineScope
) {
    private var view: NetworkView? = null
    private var job: Job? = null

    fun attachView(view: NetworkView) {
        this.view = view
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            dataSource.stateFlow.collect { (type, pingMs) ->
                val quality = NetworkQuality.from(type, pingMs)
                val text = buildText(type, pingMs, quality)
                val color = qualityColor(quality)
                withContext(Dispatchers.Main) {
                    this@NetworkPresenter.view?.showNetworkState(text, color)
                }
            }
        }
    }

    fun detach() { job?.cancel(); view = null }

    private fun buildText(type: NetworkType, pingMs: Int?, quality: NetworkQuality): String {
        val typeStr = when (type) {
            NetworkType.WIFI           -> "WiFi"
            NetworkType.CELLULAR_4G    -> "4G"
            NetworkType.CELLULAR_5G    -> "5G"
            NetworkType.CELLULAR_OTHER -> "蜂窝"
            NetworkType.ETHERNET       -> "以太网"
            NetworkType.NONE           -> "无网络"
        }
        val pingStr = pingMs?.let { "${it}ms" } ?: "--"
        val qualStr = when (quality) {
            NetworkQuality.EXCELLENT -> "极佳"
            NetworkQuality.GOOD      -> "良好"
            NetworkQuality.POOR      -> "较差"
            NetworkQuality.OFFLINE   -> "离线"
        }
        return "$typeStr · $pingStr · $qualStr"
    }

    private fun qualityColor(q: NetworkQuality) = when (q) {
        NetworkQuality.EXCELLENT -> 0xFF68D391.toInt()   // green
        NetworkQuality.GOOD      -> 0xFFFBD38D.toInt()   // yellow
        NetworkQuality.POOR      -> 0xFFFC8181.toInt()   // red
        NetworkQuality.OFFLINE   -> 0xFF888888.toInt()   // gray
    }
}
