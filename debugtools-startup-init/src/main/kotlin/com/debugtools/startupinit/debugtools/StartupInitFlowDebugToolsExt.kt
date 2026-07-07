package com.debugtools.startupinit.debugtools

import com.debugtools.startupinit.StartupInitFlow

fun StartupInitFlow.Builder.reportToStartupMonitor(): StartupInitFlow.Builder =
    reporter(StartupMonitorReporter())
