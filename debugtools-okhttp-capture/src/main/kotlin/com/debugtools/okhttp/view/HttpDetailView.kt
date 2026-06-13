package com.debugtools.okhttp.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.debugtools.okhttp.data.HttpRecord
import com.debugtools.okhttp.view.widget.HeaderFoldView
import com.debugtools.okhttp.view.widget.JsonPrettyPrinter
import com.debugtools.okhttp.view.widget.TimingWaterfallView

/**
 * Full-screen detail view for a single HTTP record.
 * Four tabs: Overview / Request / Response / Timing.
 *
 * The host (NetworkCaptureRootView) provides the close button by managing visibility.
 */
@SuppressLint("ViewConstructor")
class HttpDetailView(context: Context, private val record: HttpRecord) : LinearLayout(context) {

    private val tabBar = LinearLayout(context)
    private val content = FrameLayout(context)
    private var selectedTab = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#1A202C"))

        listOf("概览", "请求", "响应", "时序").forEachIndexed { index, label ->
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
        selectedTab = index
        for (i in 0 until tabBar.childCount) {
            tabBar.getChildAt(i).setBackgroundColor(
                if (i == index) Color.parseColor("#4A5568") else Color.TRANSPARENT
            )
        }
        content.removeAllViews()
        content.addView(when (index) {
            0 -> buildOverview()
            1 -> buildRequest()
            2 -> buildResponse()
            3 -> buildTiming()
            else -> TextView(context)
        })
    }

    private fun buildOverview(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(32, 32, 32, 32)
            addLine("方法", record.method)
            addLine("URL", record.url)
            addLine("协议", record.protocol)
            addLine("状态", if (record.failure != null) "失败: ${record.failure}" else "${record.responseCode}")
            addLine("耗时", "${record.durationMs}ms")
            addLine("请求大小", "${record.requestBody?.size ?: 0} B")
            addLine("响应大小", "${record.responseBody?.size ?: 0} B")
            addLine("时间", java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(record.timestamp)))
        })
    }

    private fun LinearLayout.addLine(name: String, value: String) {
        addView(TextView(context).apply {
            text = "$name: $value"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 14f
            setPadding(0, 8, 0, 8)
        })
    }

    private fun buildRequest(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(HeaderFoldView(context, "Request Headers", record.requestHeaders))
            addView(bodyView(record.requestBody, record.requestBodyTruncated,
                contentType(record.requestHeaders)))
        })
    }

    private fun buildResponse(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(HeaderFoldView(context, "Response Headers", record.responseHeaders))
            addView(bodyView(record.responseBody, record.responseBodyTruncated,
                contentType(record.responseHeaders)))
        })
    }

    private fun buildTiming(): View = ScrollView(context).apply {
        val t = record.timing
        if (t == null) {
            addView(TextView(context).apply {
                text = "未启用 OkHttp EventListener,无法显示分阶段耗时。\n" +
                    "总耗时: ${record.durationMs}ms"
                setTextColor(Color.parseColor("#A0AEC0"))
                textSize = 14f
                setPadding(32, 32, 32, 32)
            })
        } else {
            addView(TimingWaterfallView(context, t))
        }
    }

    private fun bodyView(body: ByteArray?, truncated: Boolean, contentType: String?): View {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(16, 16, 16, 16)
        }
        if (body == null || body.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "(no body)"
                setTextColor(Color.parseColor("#A0AEC0"))
                textSize = 14f
            })
            return container
        }
        val raw = String(body)
        val pretty = JsonPrettyPrinter.tryFormat(raw, contentType)
        container.addView(TextView(context).apply {
            text = pretty ?: raw
            setTextColor(Color.WHITE)
            textSize = 13f
            setBackgroundColor(Color.parseColor("#2D3748"))
            setPadding(16, 16, 16, 16)
        })
        if (truncated) {
            container.addView(TextView(context).apply {
                text = "⚠ 内容已截断"
                setTextColor(Color.parseColor("#FBD38D"))
                textSize = 12f
                setPadding(0, 8, 0, 0)
            })
        }
        return container
    }

    private fun contentType(headers: List<Pair<String, String>>): String? =
        headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }?.second
}
