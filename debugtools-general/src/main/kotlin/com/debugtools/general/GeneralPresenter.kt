package com.debugtools.general

import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class GeneralPresenter(
    private val diskMonitors: List<DiskMonitor>,
    private val processMonitors: List<ProcessMonitor>,
    private val scope: CoroutineScope
) {
    private var view: GeneralView? = null
    // Own independent supervisor job so collector coroutines are NOT children of the
    // caller's scope/job — this prevents runTest from flagging them as uncompleted.
    // Inherit the caller's dispatcher (ContinuationInterceptor) so test coroutines
    // run on the test dispatcher and advanceUntilIdle() drives them.
    private val supervisorJob = SupervisorJob()
    private val presenterScope = CoroutineScope(
        (scope.coroutineContext[ContinuationInterceptor] ?: Dispatchers.Main) + supervisorJob
    )
    private val jobs = mutableListOf<Job>()

    fun attachView(view: GeneralView) {
        this.view = view
        if (diskMonitors.isNotEmpty()) {
            jobs += presenterScope.launch {
                val flows = diskMonitors.map { it.sizeFlow }
                combine(flows) { sizes ->
                    diskMonitors.indices.map { i -> Pair(diskMonitors[i].path, sizes[i]) }
                }.collect { sizes ->
                    this@GeneralPresenter.view?.showDiskSizes(sizes)
                }
            }
        }
        if (processMonitors.isNotEmpty()) {
            jobs += presenterScope.launch {
                val flows = processMonitors.map { it.statesFlow }
                combine(flows) { stateMaps ->
                    stateMaps.flatMap { it.entries }.map { Pair(it.key, it.value) }
                }.collect { states ->
                    this@GeneralPresenter.view?.showProcessStates(states)
                }
            }
        }
    }

    fun detach() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        supervisorJob.cancel()
        view = null
    }
}
