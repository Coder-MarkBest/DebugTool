package com.debugtools.core.settings.binder

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingItem

internal class ToggleBinder : ItemViewBinder<SettingItem.Toggle> {
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun bind(context: Context, item: SettingItem.Toggle, storage: SettingsStorage): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 16)
        }
        row.addView(TextView(context).apply {
            text = item.label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(context).apply {
            isChecked = storage.getBoolean(item.key, item.default)
            setOnCheckedChangeListener { _, checked -> storage.putBoolean(item.key, checked) }
        })
        return row
    }
}
