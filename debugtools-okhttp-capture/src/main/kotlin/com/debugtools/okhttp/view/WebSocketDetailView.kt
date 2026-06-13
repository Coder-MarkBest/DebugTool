package com.debugtools.okhttp.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.okhttp.data.Direction
import com.debugtools.okhttp.data.FrameType
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.data.WebSocketFrame
import com.debugtools.okhttp.data.WebSocketSession
import java.text.SimpleDateFormat
import java.util.Date

@SuppressLint("ViewConstructor")
class WebSocketDetailView(
    context: Context,
    private val session: WebSocketSession,
    private val handshakeRecord: HttpRecord?  // may be null if not associated
) : LinearLayout(context) {

    private val tabBar = LinearLayout(context)
    private val content = FrameLayout(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#1A202C"))

        listOf("概览", "握手", "帧列表", "统计").forEachIndexed { index, label ->
            tabBar.addView(buildTab(label, index))
        }
        tabBar.setBackgroundColor(Color.parseColor("#2D3748"))
        addView(tabBar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        showTab(0)
    }

    private fun buildTab(label: String, index: Int): View = TextView(context).apply {
        text = label
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(48, 32, 48, 32)
        gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { showTab(index) }
    }

    private fun showTab(index: Int) {
        for (i in 0 until tabBar.childCount) {
            tabBar.getChildAt(i).setBackgroundColor(
                if (i == index) Color.parseColor("#4A5568") else Color.TRANSPARENT
            )
        }
        content.removeAllViews()
        content.addView(when (index) {
            0 -> buildOverview()
            1 -> buildHandshake()
            2 -> buildFrameList()
            3 -> buildStats()
            else -> TextView(context)
        })
    }

    private fun buildOverview(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(32, 32, 32, 32)
            line("URL", session.url)
            line("状态", stateLabel())
            line("建立时间", SimpleDateFormat("HH:mm:ss.SSS").format(Date(session.openedAt)))
            val closedAt = session.closedAt
            val duration = if (closedAt != null) closedAt - session.openedAt else System.currentTimeMillis() - session.openedAt
            line("持续", "${duration / 1000}s")
            val sendFrames = session.frames.count { it.direction == Direction.SEND }
            val recvFrames = session.frames.count { it.direction == Direction.RECEIVE }
            line("帧数", "发 $sendFrames / 收 $recvFrames")
            val sendBytes = session.frames.filter { it.direction == Direction.SEND }.sumOf { it.size.toLong() }
            val recvBytes = session.frames.filter { it.direction == Direction.RECEIVE }.sumOf { it.size.toLong() }
            line("流量", "发 ${formatBytes(sendBytes)} / 收 ${formatBytes(recvBytes)}")
        })
    }

    private fun buildHandshake(): View = if (handshakeRecord != null) {
        HttpDetailView(context, handshakeRecord)
    } else {
        TextView(context).apply {
            text = "握手记录未关联"
            setTextColor(Color.parseColor("#A0AEC0"))
            setPadding(32, 32, 32, 32)
        }
    }

    private fun buildFrameList(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            session.frames.forEach { frame ->
                addView(frameRow(frame))
            }
        })
    }

    private fun buildStats(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(32, 32, 32, 32)
            val byType = session.frames.groupBy { it.type }
            FrameType.values().forEach { type ->
                val count = byType[type]?.size ?: 0
                if (count > 0) line(type.name, "$count 帧")
            }
        })
    }

    private fun frameRow(frame: WebSocketFrame): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(16, 12, 16, 12)
        val header = TextView(context).apply {
            val arrow = if (frame.direction == Direction.SEND) "→" else "←"
            val time = SimpleDateFormat("HH:mm:ss.SSS").format(Date(frame.timestamp))
            text = "$time  $arrow  ${frame.type}  ${frame.size}B"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
        }
        addView(header)
        val body = TextView(context).apply {
            visibility = GONE
            setTextColor(Color.WHITE)
            textSize = 11f
            setBackgroundColor(Color.parseColor("#2D3748"))
            setPadding(16, 8, 16, 8)
            text = when (frame.type) {
                FrameType.TEXT -> frame.payload?.let { String(it) } ?: "(empty)"
                FrameType.BINARY -> frame.payload?.let { hexDump(it) } ?: "(empty)"
                else -> "(${frame.type.name})"
            }
        }
        addView(body)
        header.setOnClickListener { body.visibility = if (body.visibility == GONE) VISIBLE else GONE }
    }

    private fun hexDump(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            sb.append(String.format("%08x  ", i))
            val end = minOf(i + 16, bytes.size)
            for (j in i until end) sb.append(String.format("%02x ", bytes[j]))
            for (j in end until i + 16) sb.append("   ")
            sb.append(" ")
            for (j in i until end) {
                val c = bytes[j].toInt() and 0xff
                sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            sb.append('\n')
            i = end
        }
        return sb.toString()
    }

    private fun stateLabel(): String {
        if (session.failure != null) return "失败: ${session.failure}"
        return when (session.closedAt) {
            null -> "● 已连接"
            else -> "⊘ 已关闭 (${session.closeCode ?: "?"} ${session.closeReason ?: ""})"
        }
    }

    private fun LinearLayout.line(name: String, value: String) {
        addView(TextView(context).apply {
            text = "$name: $value"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            setPadding(0, 8, 0, 8)
        })
    }

    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        else -> "${b / (1024 * 1024)}MB"
    }
}
