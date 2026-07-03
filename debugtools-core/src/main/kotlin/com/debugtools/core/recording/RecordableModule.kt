package com.debugtools.core.recording

interface RecordableModule {
    val recorderId: String
    fun onRecordingStart(context: RecordingContext): ModuleRecordingSnapshot
    fun onRecordingStop(context: RecordingContext): ModuleRecordingResult
}
