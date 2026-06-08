package com.debugtools.core.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.debugtools.core.ipc.model.CrashInfo
import com.debugtools.core.ipc.model.DebugEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal class DebugToolsClient(
    private val context: Context,
    private val maxCacheSize: Int = 100,
    private val initialReconnectDelayMs: Long = 2_000L
) {
    private var service: IDebugToolsService? = null
    private val isReconnecting = AtomicBoolean(false)
    private val eventCache = CopyOnWriteArrayList<DebugEvent>()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = initialReconnectDelayMs

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IDebugToolsService.Stub.asInterface(binder)
            isReconnecting.set(false)
            reconnectDelayMs = initialReconnectDelayMs  // reset backoff
            drainCache()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            scheduleReconnect()
        }
    }

    fun connect() {
        val intent = Intent(context, DebugToolsService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun disconnect() {
        try { context.unbindService(connection) } catch (_: IllegalArgumentException) {}
        service = null
    }

    fun sendEvent(event: DebugEvent) {
        val svc = service
        if (svc != null) {
            try { svc.sendEvent(event) } catch (_: Exception) { cacheEvent(event) }
        } else {
            cacheEvent(event)
        }
    }

    fun reportCrash(crash: CrashInfo) {
        try { service?.reportCrash(crash) } catch (_: Exception) { /* best-effort */ }
    }

    private fun cacheEvent(event: DebugEvent) {
        if (eventCache.size < maxCacheSize) eventCache.add(event)
    }

    private fun drainCache() {
        val svc = service ?: return
        val pending = eventCache.toList()
        eventCache.clear()
        pending.forEach {
            try { svc.sendEvent(it) } catch (_: Exception) { cacheEvent(it) }
        }
    }

    private fun scheduleReconnect() {
        if (!isReconnecting.compareAndSet(false, true)) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (delay * 2).coerceAtMost(30_000L)  // exponential backoff, max 30s
        handler.postDelayed({
            isReconnecting.set(false)
            connect()
        }, delay)
    }
}
