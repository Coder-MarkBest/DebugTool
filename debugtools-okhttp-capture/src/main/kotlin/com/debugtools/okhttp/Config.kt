package com.debugtools.okhttp

/**
 * Tunable limits for memory/UX. All values are upper bounds applied at insertion time;
 * exceeding any limit triggers LRU eviction or truncation.
 */
data class Config(
    val maxHttpRecords: Int = 200,
    val maxWebSocketSessions: Int = 20,
    val maxFramesPerSession: Int = 500,
    val maxBodyBytes: Int = 64 * 1024,
    val maxFrameBytes: Int = 64 * 1024,
    val autoScrollPauseAfterUserScrollMs: Long = 3_000L
)
