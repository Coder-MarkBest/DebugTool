package com.debugtools.general

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File

open class DiskMonitor(
    val path: String,
    intervalMinutes: Int = 10
) {
    val intervalMinutes: Int = intervalMinutes.coerceAtLeast(5)
    private val _sizeFlow = MutableStateFlow(0L)
    open val sizeFlow: StateFlow<Long> = _sizeFlow

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                _sizeFlow.value = measureSize()
                delay(this@DiskMonitor.intervalMinutes * 60_000L)
            }
        }
    }

    open fun measureSize(): Long = try {
        File(path).walkTopDown().sumOf { file ->
            if (file.isFile) {
                try { file.length() } catch (_: SecurityException) { 0L }
            } else 0L
        }
    } catch (_: SecurityException) { 0L }
      catch (_: Exception) { 0L }
}
