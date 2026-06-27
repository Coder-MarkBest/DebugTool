package com.debugtools.audiomon.presenter

/**
 * MVP View interface for the audio monitor module.
 */
interface AudioView {
    /**
     * Show a window of raw PCM waveform samples with auto-gain.
     * @param samples Normalized PCM values (-1..1) for the visible window.
     * @param rms Current RMS level (0..1), used for auto-gain display.
     */
    fun showWaveform(samples: FloatArray, rms: Float)

    /**
     * Show FFT frequency spectrum magnitudes.
     * @param magnitudes Normalized magnitude array (0..1).
     */
    fun showSpectrum(magnitudes: FloatArray)

    /** Update the status text line. */
    fun showStatus(text: String)

    /** Update the toggle button appearance. */
    fun showMonitoringState(isMonitoring: Boolean)

    /** Register callback for the start/stop toggle button. */
    fun setToggleListener(listener: () -> Unit)

    /** Register callback for the report-last-session button. */
    fun setReportListener(listener: () -> Unit)

    /** Show the most recent finished session; reporterConfigured gates the upload button. */
    fun showLastSession(sessionId: String, summary: String, reporterConfigured: Boolean)
}
