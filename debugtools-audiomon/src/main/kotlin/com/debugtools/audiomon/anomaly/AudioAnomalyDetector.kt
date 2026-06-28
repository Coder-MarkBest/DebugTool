package com.debugtools.audiomon.anomaly

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Per-stream anomaly detector. Fed one display frame at a time; reports
 * episodes (coalesced) so a sustained anomaly yields a single event.
 * Pure JVM — no Android dependencies. Not thread-safe; call from one thread.
 */
class AudioAnomalyDetector(
    private val stream: StreamId,
    private val silenceThresholdDb: Float = -50f
) {
    private companion object {
        const val CLIP_THRESHOLD = 0.99f
        // Frame-to-frame dB delta that counts as a jump. Set above normal speech
        // dynamics (onset/syllables swing ~15-20dB) so only abnormal bursts flag.
        const val JUMP_DB = 25f
        const val NOISE_FLOOR_DB = -60f
        const val MIN_SILENCE_MS = 1000L
        const val MIN_NOISE_MS = 500L
    }

    private var prevDb: Float? = null
    private var jumpArmed = false

    private var clipOpen = false
    private var clipStart = 0L
    private var clipMaxPeak = 0f

    private var quietOpen = false
    private var quietStart = 0L
    private var quietLast = 0L
    private var quietSumDb = 0.0
    private var quietCount = 0

    fun onFrame(timeMs: Long, peak: Float, db: Float): List<AnomalyEvent> {
        val events = mutableListOf<AnomalyEvent>()

        // 1) clipping episode
        if (peak >= CLIP_THRESHOLD) {
            if (!clipOpen) {
                clipOpen = true
                clipStart = timeMs
                clipMaxPeak = peak
            } else clipMaxPeak = maxOf(clipMaxPeak, peak)
        } else if (clipOpen) {
            events += AnomalyEvent(stream, clipStart, AnomalyType.CLIPPING,
                "peak ${String.format(Locale.US, "%.2f", clipMaxPeak)}")
            clipOpen = false
        }

        // 2) energy jump (debounced — one per transition). Only counts WITHIN active
        //    audio (both frames above the silence floor), so normal speech onsets/
        //    offsets and the -90dB silence floor bouncing don't get flagged — those
        //    boundaries are the silence rule's job, not an anomaly.
        prevDb?.let { pd ->
            val active = pd >= silenceThresholdDb && db >= silenceThresholdDb
            val delta = db - pd
            if (active && abs(delta) > JUMP_DB) {
                if (!jumpArmed) {
                    val sign = if (delta > 0) "+" else ""
                    events += AnomalyEvent(stream, timeMs, AnomalyType.ENERGY_JUMP,
                        "$sign${delta.roundToInt()}dB")
                    jumpArmed = true
                }
            } else jumpArmed = false
        }
        prevDb = db

        // 3+4) quiet segment -> dropout or high noise floor on close
        if (db < silenceThresholdDb) {
            if (!quietOpen) {
                quietOpen = true
                quietStart = timeMs
                quietSumDb = 0.0
                quietCount = 0
            }
            quietSumDb += db
            quietCount++
            quietLast = timeMs
        } else if (quietOpen) {
            closeQuiet()?.let { events += it }
            quietOpen = false
        }

        return events
    }

    /** Close any open episodes at end of recording. */
    fun flush(timeMs: Long): List<AnomalyEvent> {
        val events = mutableListOf<AnomalyEvent>()
        if (clipOpen) {
            events += AnomalyEvent(stream, clipStart, AnomalyType.CLIPPING,
                "peak ${String.format(Locale.US, "%.2f", clipMaxPeak)}")
            clipOpen = false
        }
        if (quietOpen) {
            quietLast = maxOf(quietLast, timeMs)
            closeQuiet()?.let { events += it }
            quietOpen = false
        }
        return events
    }

    private fun closeQuiet(): AnomalyEvent? {
        val durMs = quietLast - quietStart
        val avg = if (quietCount > 0) quietSumDb / quietCount else NOISE_FLOOR_DB.toDouble()
        return if (avg > NOISE_FLOOR_DB) {
            if (durMs >= MIN_NOISE_MS)
                AnomalyEvent(stream, quietStart, AnomalyType.HIGH_NOISE_FLOOR, "avg ${avg.roundToInt()}dB")
            else null
        } else {
            if (durMs >= MIN_SILENCE_MS)
                AnomalyEvent(stream, quietStart, AnomalyType.SILENCE_DROPOUT, "${durMs}ms")
            else null
        }
    }
}
