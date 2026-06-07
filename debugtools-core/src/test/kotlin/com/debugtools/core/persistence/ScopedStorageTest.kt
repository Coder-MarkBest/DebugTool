package com.debugtools.core.persistence

import org.junit.Assert.*
import org.junit.Test

class ScopedStorageTest {
    // In-memory fake for testing ScopedStorage delegation
    private val delegate = object : SettingsStorage {
        val map = mutableMapOf<String, Any>()
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putStringSet(key: String, value: Set<String>) { map[key] = value }
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, default: Set<String>) = map[key] as? Set<String> ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun remove(key: String) { map.remove(key) }
        override fun clear() { map.clear() }
    }
    private val scoped = ScopedStorage("myModule", delegate)

    @Test fun `putString prefixes key with moduleId`() {
        scoped.putString("foo", "bar")
        assertEquals("bar", delegate.map["myModule/foo"])
        assertNull(delegate.map["foo"])
    }

    @Test fun `getString reads from prefixed key`() {
        delegate.map["myModule/foo"] = "bar"
        assertEquals("bar", scoped.getString("foo", ""))
    }

    @Test fun `remove deletes prefixed key`() {
        scoped.putString("a", "1")
        scoped.remove("a")
        assertNull(delegate.map["myModule/a"])
    }

    @Test fun `getBoolean with prefixed key`() {
        scoped.putBoolean("flag", true)
        assertTrue(scoped.getBoolean("flag", false))
    }

    @Test fun `putStringSet and getStringSet with prefix`() {
        scoped.putStringSet("tags", setOf("x", "y"))
        assertEquals(setOf("x", "y"), scoped.getStringSet("tags", emptySet()))
    }

    @Test fun `clear is a no-op on opaque delegates`() {
        // ScopedStorage cannot safely enumerate keys from an opaque SettingsStorage delegate.
        // Callers that need scoped-only clear must call remove() per key.
        // This test verifies no exception is thrown.
        scoped.putString("a", "1")
        scoped.clear()  // no exception
    }
}
