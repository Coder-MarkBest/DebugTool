package com.debugtools.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.debugtools.network.model.NetworkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.InetAddress

interface NetworkDataSource {
    val stateFlow: Flow<Pair<NetworkType, Int?>>
}

class DefaultNetworkDataSource(
    private val context: Context,
    private val gateway: String,
    private val pollIntervalMs: Long = 5_000L
) : NetworkDataSource {
    override val stateFlow: Flow<Pair<NetworkType, Int?>> = flow {
        while (true) {
            val type = getNetworkType()
            val ping = if (type != NetworkType.NONE) measurePing(gateway) else null
            emit(Pair(type, ping))
            delay(pollIntervalMs)
        }
    }.flowOn(Dispatchers.IO)

    private fun getNetworkType(): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkType.NONE
        return when {
            nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
            nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR_4G
            else -> NetworkType.NONE
        }
    }

    private fun measurePing(host: String): Int? = try {
        val start = System.currentTimeMillis()
        InetAddress.getByName(host).isReachable(3_000)
        (System.currentTimeMillis() - start).toInt()
    } catch (_: Exception) { null }
}
