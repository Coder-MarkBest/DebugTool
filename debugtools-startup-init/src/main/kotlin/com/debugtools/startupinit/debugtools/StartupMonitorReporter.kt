package com.debugtools.startupinit.debugtools

import com.debugtools.startup.AppStartupMonitor
import com.debugtools.startupinit.InitReporter

class StartupMonitorReporter : InitReporter {
    override fun flowStarted(taskCount: Int) = Unit

    override fun taskStarted(name: String, dependsOn: List<String>) {
        AppStartupMonitor.begin(name, dependsOn)
    }

    override fun taskSucceeded(name: String) {
        AppStartupMonitor.success(name)
    }

    override fun taskFailed(name: String, error: Throwable) {
        AppStartupMonitor.fail(name, error)
    }

    override fun taskSkipped(name: String, failedDependencies: List<String>) {
        val synthetic = "init_flow_skipped:$name"
        AppStartupMonitor.begin(synthetic, failedDependencies)
        AppStartupMonitor.fail(
            synthetic,
            "Skipped because dependencies failed: ${failedDependencies.joinToString(",")}"
        )
    }

    override fun flowFailed(reason: String) {
        AppStartupMonitor.begin("init_flow_invalid_graph")
        AppStartupMonitor.fail("init_flow_invalid_graph", reason)
    }

    override fun flowCompleted() {
        AppStartupMonitor.complete()
    }
}
