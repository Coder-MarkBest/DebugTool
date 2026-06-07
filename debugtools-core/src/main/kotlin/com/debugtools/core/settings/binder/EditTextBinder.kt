package com.debugtools.core.settings.binder

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingItem

internal class EditTextBinder : ItemViewBinder<SettingItem.EditText> {
    override fun bind(context: Context, item: SettingItem.EditText, storage: SettingsStorage): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 8)
        }
        container.addView(TextView(context).apply { text = item.label })
        val editText = EditText(context).apply {
            setText(storage.getString(item.key, item.default))
            hint = item.hint
        }
        container.addView(editText)
        container.addView(Button(context).apply {
            text = "确认"
            setOnClickListener { storage.putString(item.key, editText.text.toString()) }
        })
        return container
    }
}
