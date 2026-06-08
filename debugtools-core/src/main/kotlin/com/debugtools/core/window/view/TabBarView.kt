package com.debugtools.core.window.view

import android.content.Context
import android.graphics.Color
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.module.DebugModule

internal class TabBarView(context: Context) : HorizontalScrollView(context) {
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private var selectedIndex = 0
    var onTabSelected: ((Int) -> Unit)? = null

    init { addView(container) }

    fun setTabs(modules: List<DebugModule>) {
        container.removeAllViews()
        modules.forEachIndexed { index, module ->
            container.addView(buildTab(context, module.tabTitle, index == selectedIndex) {
                selectTab(index)
            })
        }
    }

    private fun buildTab(context: Context, title: String, selected: Boolean,
                         onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = title
            setPadding(32, 16, 32, 16)
            setTextColor(if (selected) Color.WHITE else Color.GRAY)
            setOnClickListener { onClick() }
        }

    private fun selectTab(index: Int) {
        selectedIndex = index
        onTabSelected?.invoke(index)
        for (i in 0 until container.childCount) {
            (container.getChildAt(i) as? TextView)
                ?.setTextColor(if (i == index) Color.WHITE else Color.GRAY)
        }
    }
}
