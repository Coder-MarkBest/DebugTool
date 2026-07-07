package com.debugtools.startupinit

interface InitReporter {
    fun flowStarted(taskCount: Int) = Unit
    fun taskStarted(name: String, dependsOn: List<String>) = Unit
    fun taskSucceeded(name: String) = Unit
    fun taskFailed(name: String, error: Throwable) = Unit
    fun taskSkipped(name: String, failedDependencies: List<String>) = Unit
    fun flowFailed(reason: String) = Unit
    fun flowCompleted() = Unit
}

object NoOpInitReporter : InitReporter
