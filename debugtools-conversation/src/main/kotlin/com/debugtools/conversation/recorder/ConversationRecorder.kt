package com.debugtools.conversation.recorder

import com.debugtools.conversation.protocol.ConversationSession
import com.debugtools.conversation.protocol.ConversationTurn

/**
 * Accumulates turns for one conversation session. Thread-safe.
 * Pure JVM — [clock] is injectable for unit testing.
 */
class ConversationRecorder(
    private val sessionId: String,
    private val startedAtWallMs: Long,
    private var metadata: Map<String, String>? = null,
    private val clock: () -> Long
) {
    private val lock = Any()
    private val turns = mutableListOf<ConversationTurn>()
    private var endedAtWallMs: Long? = null

    fun startSession(meta: Map<String, String>?) = synchronized(lock) {
        if (metadata == null) metadata = meta
    }

    fun submitTurn(turn: ConversationTurn) = synchronized(lock) {
        turns.add(turn)
    }

    fun endSession() = synchronized(lock) {
        if (endedAtWallMs == null) { endedAtWallMs = clock() }
    }

    /**
     * Safety-net: if host never calls endSession(), finalizes with the current
     * clock time. Never overrides an explicit endSession().
     */
    fun finalizeFallback() = synchronized(lock) {
        if (endedAtWallMs == null) { endedAtWallMs = clock() }
    }

    fun isEnded(): Boolean = synchronized(lock) { endedAtWallMs != null }

    fun snapshot(): ConversationSession = synchronized(lock) {
        ConversationSession(
            sessionId = sessionId,
            startedAtWallMs = startedAtWallMs,
            metadata = metadata,
            turns = turns.toList(),
            endedAtWallMs = endedAtWallMs
        )
    }
}
