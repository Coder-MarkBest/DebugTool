package com.debugtools.core.module

import android.content.Context
import android.view.View
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup

interface DebugModule {
    val moduleId: String
    val tabTitle: String

    /** Declares setting groups for this module's settings tab. */
    fun buildSettings(): List<SettingGroup>

    /** Returns the View shown in this module's expanded tab. */
    fun createContentView(context: Context): View

    /** Returns items shown in the Brief (compact) overlay. Empty = not shown. */
    fun getBriefItems(): List<BriefItem>

    /** Called when the module is attached. Storage is already scoped to this module's ID. */
    fun onAttach(context: Context, storage: SettingsStorage)

    /** Called when the module is detached. Cancel coroutines and release resources. */
    fun onDetach()
}
