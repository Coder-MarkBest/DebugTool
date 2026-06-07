package com.debugtools.core.settings

import android.content.Context
import android.view.View
import com.debugtools.core.persistence.SettingsStorage

sealed class SettingItem(
    val key: String,
    val label: String,
    val description: String? = null   // null = description block not shown
) {
    class SingleSelect(
        key: String,
        label: String,
        val options: List<String>,
        val default: String,
        description: String? = null
    ) : SettingItem(key, label, description)

    class MultiSelect(
        key: String,
        label: String,
        val options: List<String>,
        val defaults: List<String>,
        description: String? = null
    ) : SettingItem(key, label, description)

    class Toggle(
        key: String,
        label: String,
        val default: Boolean,
        description: String? = null
    ) : SettingItem(key, label, description)

    class EditText(
        key: String,
        label: String,
        val default: String,
        val hint: String = "",
        description: String? = null
    ) : SettingItem(key, label, description)

    class Custom(
        key: String,
        label: String,
        val viewFactory: (Context, SettingsStorage) -> View,
        description: String? = null
    ) : SettingItem(key, label, description)
}
