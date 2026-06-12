package com.debugtools.okhttp.presenter

import com.debugtools.okhttp.repository.NetworkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Converts [NetworkRepository.state] into a flat [ListItem] list, throttled by [sampleMs],
 * and pushes to [NetworkCaptureView] on the scope's dispatcher.
 *
 * Expansion state per WebSocket session is tracked locally (collapsed sessions only).
 */
class NetworkCapturePresenter(
    private val repository: NetworkRepository,
    private val scope: CoroutineScope,
    private val sampleMs: Long = 100L
) {
    private var view: NetworkCaptureView? = null
    private var job: Job? = null
    private val collapsedSessions = MutableStateFlow<Set<String>>(emptySet())

    fun attachView(view: NetworkCaptureView) {
        this.view = view
        job = scope.launch {
            val source = combine(repository.state, collapsedSessions) { snap, collapsed ->
                buildItems(snap, collapsed)
            }
                .let { if (sampleMs > 0) it.sample(sampleMs) else it }
                .distinctUntilChanged()
            source.collect { items -> this@NetworkCapturePresenter.view?.showItems(items) }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        view = null
    }

    fun toggleSessionExpanded(sessionId: String) {
        val current = collapsedSessions.value
        collapsedSessions.value = if (sessionId in current) current - sessionId
                                  else current + sessionId
    }

    private fun buildItems(
        snap: NetworkRepository.Snapshot,
        collapsed: Set<String>
    ): List<ListItem> {
        val out = mutableListOf<ListItem>()
        out += snap.httpRecords.map { ListItem.HttpRow(it) }
        snap.webSocketSessions.forEach { session ->
            val isExpanded = session.sessionId !in collapsed
            out += ListItem.WebSocketSessionRow(session, expanded = isExpanded)
            if (isExpanded) {
                session.frames.forEach { frame ->
                    out += ListItem.WebSocketFrameRow(frame)
                }
            }
        }
        return out.sortedBy { it.timestamp }
    }
}
