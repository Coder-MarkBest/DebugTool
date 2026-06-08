package com.debugtools.timeline

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.debugtools.core.ipc.model.DebugEvent
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup

class TimelineModule private constructor(maxSize: Int) : DebugModule {
    override val moduleId = "debugtools_timeline"
    override val tabTitle = "流程"

    private val repository = EventRepository(maxSize)
    private val presenter = TimelinePresenter(repository)
    private val adapter = TimelineAdapter()
    private var lastEvent: DebugEvent? = null

    override fun buildSettings() = emptyList<SettingGroup>()

    override fun createContentView(context: Context): View {
        val layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
        return RecyclerView(context).apply {
            this.layoutManager = layoutManager
            adapter = this@TimelineModule.adapter
        }.also { rv ->
            presenter.attachView(object : TimelineView {
                override fun showEvents(events: List<DebugEvent>) {
                    lastEvent = events.lastOrNull()
                    adapter.submitList(events.toList())
                    if (events.isNotEmpty()) rv.scrollToPosition(events.size - 1)
                }
            })
        }
    }

    override fun getBriefItems(): List<BriefItem> {
        val event = lastEvent ?: return emptyList()
        val agoSec = (System.currentTimeMillis() - event.timestamp) / 1000
        val ago = if (agoSec < 60) "${agoSec}s ago" else "${agoSec / 60}m ago"
        return listOf(BriefItem("${event.tag} · $ago"))
    }

    override fun onAttach(context: Context, storage: SettingsStorage) {}
    override fun onDetach() { presenter.detach() }

    fun onEvent(event: DebugEvent) = presenter.onEvent(event)

    companion object {
        fun create(maxSize: Int = 500) = TimelineModule(maxSize)
    }
}
