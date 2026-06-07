package com.debugtools.core.persistence

internal class ScopedStorage(
    private val moduleId: String,
    private val delegate: SettingsStorage
) : SettingsStorage {
    init {
        require(moduleId.isNotEmpty() && !moduleId.contains("/")) {
            "moduleId must be non-empty and must not contain '/': '$moduleId'"
        }
    }

    private fun k(key: String) = "$moduleId/$key"

    override fun putString(key: String, value: String) = delegate.putString(k(key), value)
    override fun getString(key: String, default: String) = delegate.getString(k(key), default)
    override fun putStringSet(key: String, value: Set<String>) = delegate.putStringSet(k(key), value)
    override fun getStringSet(key: String, default: Set<String>) = delegate.getStringSet(k(key), default)
    override fun putBoolean(key: String, value: Boolean) = delegate.putBoolean(k(key), value)
    override fun getBoolean(key: String, default: Boolean) = delegate.getBoolean(k(key), default)
    override fun remove(key: String) = delegate.remove(k(key))
    override fun clear() { /* no-op: can't safely enumerate keys from opaque delegate */ }
}
