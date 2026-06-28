package com.debugtools.audiomon.presenter

import com.debugtools.audiomon.anomaly.AnomalyEvent
import com.debugtools.audiomon.anomaly.StreamId

/** MVP View interface for the audio monitor module. */
interface AudioView {
    /** Update the status text line (also used for the recording countdown). */
    fun showStatus(text: String)

    /** Update the record button appearance. */
    fun showMonitoringState(isRecording: Boolean)

    /** Register callback for the start/stop record button. */
    fun setToggleListener(listener: () -> Unit)

    /** Register callback for the report-last-session button. */
    fun setReportListener(listener: () -> Unit)

    /** Show the most recent finished session; reporterConfigured gates the upload button. */
    fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean)

    /** Reset both lanes and the anomaly list at the start of a recording. */
    fun clearLive()

    /** Append one display frame to a stream's lane (envelope uses db, spectrogram uses spectrum). */
    fun pushLiveFrame(stream: StreamId, db: Float, spectrum: FloatArray)

    /** Mark the latest column of the stream's lane anomalous + append to the anomaly list. */
    fun showAnomaly(event: AnomalyEvent)
}
