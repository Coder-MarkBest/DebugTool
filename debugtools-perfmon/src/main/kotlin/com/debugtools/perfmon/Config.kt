package com.debugtools.perfmon

/**
 * Tunable settings for the perf module. All values are upper bounds applied at sample
 * time; out-of-range values are clamped by the [PerfMonitorModule.Builder].
 */
data class Config(
    val updateIntervalSec: Int = 10,
    val windowMin: Int = 30,
    val cpuOrangeThreshold: Int = 50,
    val cpuRedThreshold: Int = 80,
    val pssRedThresholdMb: Int = 0,   // 0 = no memory alert
    val topThreadCount: Int = 10
) {
    val windowSec: Int get() = windowMin * 60
}
