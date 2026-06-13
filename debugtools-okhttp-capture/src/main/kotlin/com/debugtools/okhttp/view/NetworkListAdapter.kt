package com.debugtools.okhttp.view

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.data.WebSocketSession
import com.debugtools.okhttp.presenter.ListItem
import java.text.SimpleDateFormat
import java.util.Date

class NetworkListAdapter(
    private val onHttpClick: (HttpRecord) -> Unit,
    private val onWebSocketClick: (WebSocketSession) -> Unit,
    private val onSessionToggle: (String) -> Unit
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_HTTP = 0
        private const val TYPE_WS_SESSION = 1
        private const val TYPE_WS_FRAME = 2

        private val DIFF = object : DiffUtil.ItemCallback<ListItem>() {
            override fun areItemsTheSame(a: ListItem, b: ListItem) = a.id == b.id
            override fun areContentsTheSame(a: ListItem, b: ListItem): Boolean {
                if (a::class != b::class) return false
                return when (a) {
                    is ListItem.HttpRow -> a.record == (b as ListItem.HttpRow).record
                    is ListItem.WebSocketSessionRow -> {
                        val bb = b as ListItem.WebSocketSessionRow
                        a.session.sessionId == bb.session.sessionId &&
                            a.session.closedAt == bb.session.closedAt &&
                            a.session.frames.size == bb.session.frames.size &&
                            a.expanded == bb.expanded
                    }
                    is ListItem.WebSocketFrameRow -> a.frame == (b as ListItem.WebSocketFrameRow).frame
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ListItem.HttpRow -> TYPE_HTTP
        is ListItem.WebSocketSessionRow -> TYPE_WS_SESSION
        is ListItem.WebSocketFrameRow -> TYPE_WS_FRAME
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 24, 24, 24)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }
        return object : RecyclerView.ViewHolder(row) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val row = holder.itemView as LinearLayout
        row.removeAllViews()
        when (item) {
            is ListItem.HttpRow -> bindHttp(row, item.record)
            is ListItem.WebSocketSessionRow -> bindWsSession(row, item.session, item.expanded)
            is ListItem.WebSocketFrameRow -> bindWsFrame(row, item)
        }
    }

    private fun bindHttp(row: LinearLayout, record: HttpRecord) {
        row.setBackgroundColor(Color.TRANSPARENT)
        row.setOnClickListener { onHttpClick(record) }

        val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date(record.timestamp))
        val urlPath = record.url.substringAfter("//").substringAfter("/").let { "/$it" }
        val statusColor = when {
            record.failure != null -> "#FC8181"
            record.responseCode >= 500 -> "#FC8181"
            record.responseCode >= 400 -> "#FBD38D"
            else -> "#E2E8F0"
        }
        row.addView(TextView(row.context).apply {
            text = time; setTextColor(Color.parseColor("#A0AEC0"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        })
        row.addView(TextView(row.context).apply {
            text = "${record.method} $urlPath"
            setTextColor(Color.parseColor(statusColor))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f)
        })
        row.addView(TextView(row.context).apply {
            text = if (record.failure != null) "FAIL" else "${record.responseCode} ${record.durationMs}ms"
            setTextColor(Color.parseColor(statusColor))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        })
    }

    private fun bindWsSession(row: LinearLayout, session: WebSocketSession, expanded: Boolean) {
        row.setBackgroundColor(Color.parseColor("#202C3A"))
        row.setOnClickListener { onWebSocketClick(session) }

        val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date(session.openedAt))
        val urlShort = session.url.substringAfter("//").substringAfter("/").let { "/$it" }
        val state = when {
            session.failure != null -> "✗"
            session.closedAt == null -> "● Open"
            else -> "⊘ ${session.closeCode ?: ""}"
        }
        val color = when {
            session.failure != null -> "#FC8181"
            session.closedAt == null -> "#68D391"
            else -> "#A0AEC0"
        }
        row.addView(TextView(row.context).apply {
            text = time; setTextColor(Color.parseColor("#A0AEC0"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
        })
        row.addView(TextView(row.context).apply {
            text = "WS $urlShort"
            setTextColor(Color.parseColor(color))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5f)
        })
        row.addView(TextView(row.context).apply {
            text = "$state ${session.frames.size}f ${if (expanded) "▼" else "▶"}"
            setTextColor(Color.parseColor(color))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener { onSessionToggle(session.sessionId) }
        })
    }

    private fun bindWsFrame(row: LinearLayout, item: ListItem.WebSocketFrameRow) {
        row.setBackgroundColor(Color.parseColor("#1A222C"))
        row.setPadding(64, 16, 24, 16)
        val frame = item.frame
        val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date(frame.timestamp))
        val arrow = if (frame.direction == Direction.SEND) "→" else "←"
        val preview = when (frame.type) {
            FrameType.TEXT -> frame.payload?.let { String(it).take(48) } ?: ""
            FrameType.BINARY -> "[hex ${frame.size}B]"
            else -> "(${frame.type.name})"
        }
        row.addView(TextView(row.context).apply {
            text = "$time  $arrow  ${frame.type}  ${frame.size}B  $preview"
            setTextColor(Color.parseColor("#CBD5E0"))
            textSize = 13f
        })
    }
}
