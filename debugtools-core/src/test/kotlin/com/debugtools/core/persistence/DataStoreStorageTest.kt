package com.debugtools.core.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreStorageTest {
    private lateinit var storage: DataStoreStorage

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // Use unique name per test run to avoid state leakage between tests
        storage = DataStoreStorage(ctx, "test_ds_${System.nanoTime()}")
    }

    @After fun tearDown() = storage.close()

    @Test fun `getString returns default when absent`() =
        assertEquals("def", storage.getString("x", "def"))

    @Test fun `putString and getString roundtrip`() {
        storage.putString("k", "hello")
        assertEquals("hello", storage.getString("k", ""))
    }

    @Test fun `putBoolean and getBoolean roundtrip`() {
        storage.putBoolean("b", true)
        assertTrue(storage.getBoolean("b", false))
    }

    @Test fun `putStringSet and getStringSet roundtrip`() {
        storage.putStringSet("s", setOf("x", "y"))
        assertEquals(setOf("x", "y"), storage.getStringSet("s", emptySet()))
    }

    @Test fun `remove deletes key`() {
        storage.putString("k", "v")
        storage.remove("k")
        assertEquals("def", storage.getString("k", "def"))
    }

    @Test fun `clear removes all keys`() {
        storage.putString("k1", "v1")
        storage.putBoolean("k2", true)
        storage.clear()
        assertEquals("def", storage.getString("k1", "def"))
        assertFalse(storage.getBoolean("k2", false))
    }
}
