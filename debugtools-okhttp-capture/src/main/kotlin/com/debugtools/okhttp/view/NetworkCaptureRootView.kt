package com.debugtools.okhttp.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.debugtools.okhttp.Config
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.data.WebSocketSession
import com.debugtools.okhttp.presenter.ListItem
import com.debugtools.okhttp.presenter.NetworkCaptureView
import com.debugtools.okhttp.repository.NetworkRepository

/**
 * Tab content view. Hosts a RecyclerView for the mixed list + a detail overlay
 * (HttpDetailView or WebSocketDetailView) shown when an item is tapped.
 *
 * Auto-scroll: new items append to the bottom and the view scrolls to make them visible.
 * When the user touches the list and scrolls away from the bottom, follow mode pauses.
 * After [Config.autoScrollPauseAfterUserScrollMs] of no scroll, follow mode resumes.
 */
@SuppressLint("ViewConstructor")
class NetworkCaptureRootView(
    context: Context,
    private val config: Config,
    private val repository: NetworkRepository,
    onToggleSession: (String) -> Unit
) : FrameLayout(context), NetworkCaptureView {

    private val recycler: RecyclerView
    private lateinit var followButton: Button
    private val detailContainer: FrameLayout
    private val adapter: NetworkListAdapter

    private var followLatest = true
    private val resumeRunnable = Runnable {
        followLatest = true
        followButton.visibility = GONE
        scrollToEndIfNeeded()
    }

    init {
        setBackgroundColor(Color.parseColor("#1A1A2E"))
        adapter = NetworkListAdapter(
            onHttpClick = ::showHttpDetail,
            onWebSocketClick = ::showWebSocketDetail,
            onSessionToggle = onToggleSession
        )
        recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = this@NetworkCaptureRootView.adapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        followLatest = false
                        followButton.visibility = VISIBLE
                        rv.removeCallbacks(resumeRunnable)
                        rv.postDelayed(resumeRunnable, config.autoScrollPauseAfterUserScrollMs)
                    }
                }
            })
        }
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        detailContainer = FrameLayout(context).apply {
            visibility = GONE
            setBackgroundColor(Color.parseColor("#1A202C"))
        }
        addView(detailContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        followButton = Button(context).apply {
            text = "📍 跟随最新"
            setBackgroundColor(Color.parseColor("#63B3ED"))
            setTextColor(Color.WHITE)
            visibility = GONE
            setOnClickListener {
                followLatest = true
                visibility = GONE
                scrollToEndIfNeeded()
            }
        }
        addView(followButton, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            setMargins(0, 0, 32, 32)
        })
    }

    override fun showItems(items: List<ListItem>) {
        adapter.submitList(items) { scrollToEndIfNeeded() }
    }

    private fun scrollToEndIfNeeded() {
        if (!followLatest) return
        val count = adapter.itemCount
        if (count > 0) recycler.scrollToPosition(count - 1)
    }

    private fun showHttpDetail(record: HttpRecord) {
        detailContainer.removeAllViews()
        detailContainer.addView(HttpDetailView(context, record))
        addCloseButton()
        detailContainer.visibility = VISIBLE
    }

    private fun showWebSocketDetail(session: WebSocketSession) {
        detailContainer.removeAllViews()
        val handshake = repository.snapshot().httpRecords.firstOrNull {
            it.webSocketSessionId == session.sessionId || it.url == session.url
        }
        detailContainer.addView(WebSocketDetailView(context, session, handshake))
        addCloseButton()
        detailContainer.visibility = VISIBLE
    }

    private fun addCloseButton() {
        val close = Button(context).apply {
            text = "← 返回"
            setBackgroundColor(Color.parseColor("#2D3748"))
            setTextColor(Color.WHITE)
            setOnClickListener { detailContainer.visibility = GONE }
        }
        detailContainer.addView(close, LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setMargins(16, 16, 0, 0)
        })
    }
}
