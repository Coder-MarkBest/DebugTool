package com.debugtools.core.persistence

interface SettingsStorage {
    fun putString(key: String, value: String)
    fun getString(key: String, default: String): String
    fun putStringSet(key: String, value: Set<String>)
    fun getStringSet(key: String, default: Set<String>): Set<String>
    fun putBoolean(key: String, value: Boolean)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun remove(key: String)
    fun clear()
}
