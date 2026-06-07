package com.debugtools.core.settings.binder

import android.content.Context
import android.view.View
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingItem

internal interface ItemViewBinder<T : SettingItem> {
    fun bind(context: Context, item: T, storage: SettingsStorage): View
}
