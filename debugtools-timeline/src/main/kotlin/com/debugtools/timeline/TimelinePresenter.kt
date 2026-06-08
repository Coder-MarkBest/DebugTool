package com.debugtools.timeline

import com.debugtools.core.ipc.model.DebugEvent

class TimelinePresenter(private val repository: EventRepository) {
    private var view: TimelineView? = null

    fun attachView(view: TimelineView) {
        this.view = view
        view.showEvents(repository.snapshot())
    }

    fun detach() { view = null }

    fun onEvent(event: DebugEvent) {
        repository.add(event)
        view?.showEvents(repository.snapshot())
    }
}
