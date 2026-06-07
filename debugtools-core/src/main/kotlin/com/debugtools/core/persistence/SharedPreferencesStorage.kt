package com.debugtools.core.persistence

import android.content.Context
import android.content.Context.MODE_PRIVATE

class SharedPreferencesStorage(
    context: Context,
    name: String = "debugtools_settings"
) : SettingsStorage {
    private val prefs = context.getSharedPreferences(name, MODE_PRIVATE)

    override fun putString(key: String, value: String) =
        prefs.edit().putString(key, value).apply()
    override fun getString(key: String, default: String) =
        prefs.getString(key, default) ?: default
    override fun putStringSet(key: String, value: Set<String>) =
        prefs.edit().putStringSet(key, value).apply()
    override fun getStringSet(key: String, default: Set<String>) =
        prefs.getStringSet(key, default)?.toSet() ?: default
    override fun putBoolean(key: String, value: Boolean) =
        prefs.edit().putBoolean(key, value).apply()
    override fun getBoolean(key: String, default: Boolean) =
        prefs.getBoolean(key, default)
    override fun remove(key: String) = prefs.edit().remove(key).apply()
    override fun clear() = prefs.edit().clear().apply()
}
