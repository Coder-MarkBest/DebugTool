package com.debugtools.core

import android.content.Context
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.persistence.SharedPreferencesStorage
import com.debugtools.core.window.BriefOrientation

class DebugToolsBuilder internal constructor(internal val context: Context) {
    var processMode: ProcessMode = ProcessMode.ATTACHED
        internal set
    var storage: SettingsStorage = SharedPreferencesStorage(context)
        internal set
    var briefOrientation: BriefOrientation = BriefOrientation.VERTICAL
        internal set
    internal val modules = mutableListOf<DebugModule>()

    fun processMode(mode: ProcessMode) = apply { processMode = mode }
    fun storage(storage: SettingsStorage) = apply { this.storage = storage }
    fun briefOrientation(orientation: BriefOrientation) = apply { briefOrientation = orientation }
    fun register(module: DebugModule) = apply { modules.add(module) }
    fun build(): DebugTools = DebugTools.create(this)
}
