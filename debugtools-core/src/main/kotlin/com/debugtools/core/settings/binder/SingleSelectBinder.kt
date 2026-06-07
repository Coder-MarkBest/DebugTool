package com.debugtools.core.settings.binder

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingItem

internal class SingleSelectBinder : ItemViewBinder<SettingItem.SingleSelect> {
    override fun bind(context: Context, item: SettingItem.SingleSelect, storage: SettingsStorage): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        container.addView(TextView(context).apply { text = item.label })
        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 0)
        }
        fun refreshPills() {
            val current = storage.getString(item.key, item.default)
            for (i in 0 until pillRow.childCount) {
                val pill = pillRow.getChildAt(i) as TextView
                val selected = pill.tag == current
                pill.setBackgroundColor(if (selected) Color.parseColor("#4A90E2") else Color.parseColor("#555555"))
            }
        }
        item.options.forEach { option ->
            pillRow.addView(TextView(context).apply {
                text = option
                tag = option
                setPadding(24, 8, 24, 8)
                setTextColor(Color.WHITE)
                setBackgroundColor(
                    if (option == storage.getString(item.key, item.default))
                        Color.parseColor("#4A90E2") else Color.parseColor("#555555")
                )
                setOnClickListener {
                    storage.putString(item.key, option)
                    refreshPills()
                }
            })
        }
        container.addView(pillRow)
        return container
    }
}
