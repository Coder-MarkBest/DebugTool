package com.debugtools.core.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesStorageTest {
    private lateinit var storage: SharedPreferencesStorage

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        storage = SharedPreferencesStorage(ctx, "test")
    }

    @Test fun `getString returns default when absent`() =
        assertEquals("def", storage.getString("x", "def"))

    @Test fun `putString and getString roundtrip`() {
        storage.putString("k", "v")
        assertEquals("v", storage.getString("k", ""))
    }

    @Test fun `putBoolean and getBoolean roundtrip`() {
        storage.putBoolean("flag", true)
        assertTrue(storage.getBoolean("flag", false))
    }

    @Test fun `putStringSet and getStringSet roundtrip`() {
        storage.putStringSet("set", setOf("a", "b"))
        assertEquals(setOf("a", "b"), storage.getStringSet("set", emptySet()))
    }

    @Test fun `remove deletes key`() {
        storage.putString("k", "v")
        storage.remove("k")
        assertEquals("def", storage.getString("k", "def"))
    }

    @Test fun `clear removes all keys`() {
        storage.putString("k1", "v1")
        storage.putString("k2", "v2")
        storage.clear()
        assertEquals("def", storage.getString("k1", "def"))
    }
}
