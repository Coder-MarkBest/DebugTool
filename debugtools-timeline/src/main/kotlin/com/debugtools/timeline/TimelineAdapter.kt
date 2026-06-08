package com.debugtools.timeline

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.debugtools.core.ipc.model.DebugEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimelineAdapter : ListAdapter<DebugEvent, TimelineAdapter.ViewHolder>(DIFF) {
    private val expandedKeys = mutableSetOf<Long>()

    inner class ViewHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val timeView = TextView(root.context)
        val tagView = TextView(root.context)
        val detailView = TextView(root.context).apply {
            visibility = View.GONE
            setTextColor(Color.GRAY)
            textSize = 11f
            setPadding(0, 4, 0, 0)
        }

        init {
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(16, 8, 16, 8)
            val row = LinearLayout(root.context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            timeView.setTextColor(Color.GRAY)
            timeView.textSize = 11f
            row.addView(timeView)
            row.addView(tagView)
            root.addView(row)
            root.addView(detailView)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LinearLayout(parent.context))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = getItem(position)
        holder.timeView.text = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(event.timestamp)) + "  "
        holder.tagView.text = event.tag

        if (event.detail != null) {
            val expanded = event.timestamp in expandedKeys
            holder.detailView.text = event.detail
            holder.detailView.visibility = if (expanded) View.VISIBLE else View.GONE
            holder.root.setOnClickListener {
                if (event.timestamp in expandedKeys) expandedKeys.remove(event.timestamp)
                else expandedKeys.add(event.timestamp)
                notifyItemChanged(position)
            }
        } else {
            holder.detailView.visibility = View.GONE
            holder.root.setOnClickListener(null)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DebugEvent>() {
            override fun areItemsTheSame(a: DebugEvent, b: DebugEvent) = a.timestamp == b.timestamp
            override fun areContentsTheSame(a: DebugEvent, b: DebugEvent) = a == b
        }
    }
}
