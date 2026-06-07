package com.debugtools.core.settings.binder

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingItem

internal class MultiSelectBinder : ItemViewBinder<SettingItem.MultiSelect> {
    override fun bind(context: Context, item: SettingItem.MultiSelect, storage: SettingsStorage): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        container.addView(TextView(context).apply { text = item.label })
        val current = storage.getStringSet(item.key, item.defaults.toSet()).toMutableSet()
        item.options.forEach { option ->
            container.addView(CheckBox(context).apply {
                text = option
                isChecked = option in current
                setOnCheckedChangeListener { _, checked ->
                    if (checked) current.add(option) else current.remove(option)
                    storage.putStringSet(item.key, current.toSet())
                }
            })
        }
        return container
    }
}
