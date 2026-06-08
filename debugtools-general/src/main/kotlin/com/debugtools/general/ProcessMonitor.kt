package com.debugtools.general

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

open class ProcessMonitor(
    val processNames: List<String>,
    private val checkIntervalMs: Long = 10_000L
) {
    private val _statesFlow = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    open val statesFlow: StateFlow<Map<String, Boolean>> = _statesFlow

    fun start(context: Context, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                _statesFlow.value = checkProcesses(context)
                delay(checkIntervalMs)
            }
        }
    }

    private fun checkProcesses(context: Context): Map<String, Boolean> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val running = am.runningAppProcesses?.map { it.processName }?.toSet() ?: emptySet()
        return processNames.associateWith { it in running }
    }
}
