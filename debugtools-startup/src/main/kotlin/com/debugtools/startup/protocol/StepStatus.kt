package com.debugtools.startup.protocol

/** A startup step is RUNNING until it ends as SUCCESS or FAILED (an error also ends it). */
enum class StepStatus { RUNNING, SUCCESS, FAILED }
