package com.debugtools.perfmon.data

/** What the caller asked us to monitor. */
sealed class ProcessTarget {
    abstract val key: String  // stable identity for UI selection

    data class ByName(val processName: String) : ProcessTarget() {
        override val key: String get() = "name:$processName"
    }

    data class ByPid(val pid: Int) : ProcessTarget() {
        override val key: String get() = "pid:$pid"
    }
}
