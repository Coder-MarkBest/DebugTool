package com.debugtools.core.settings.binder

import android.content.Context
import android.view.View
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingItem

internal class CustomBinder : ItemViewBinder<SettingItem.Custom> {
    override fun bind(context: Context, item: SettingItem.Custom, storage: SettingsStorage): View =
        item.viewFactory(context, storage)
}
