// SettingsRendererTest.kt
package com.debugtools.core.settings

import android.content.Context
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.debugtools.core.persistence.SharedPreferencesStorage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRendererTest {
    private lateinit var context: Context
    private lateinit var storage: SharedPreferencesStorage
    private lateinit var renderer: SettingsRenderer

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = SharedPreferencesStorage(context, "renderer_test_${System.nanoTime()}")
        renderer = SettingsRenderer()
    }

    @Test fun `render returns non-null view for empty groups`() {
        val view = renderer.render(context, emptyList(), storage)
        assertNotNull(view)
    }

    @Test fun `render creates one child per group`() {
        val groups = listOf(
            SettingGroup("Group A", listOf(SettingItem.Toggle("t1", "T1", true))),
            SettingGroup("Group B", listOf(SettingItem.Toggle("t2", "T2", false)))
        )
        val view = renderer.render(context, groups, storage) as LinearLayout
        assertEquals(2, view.childCount)
    }

    @Test fun `Toggle writes default to storage on first render`() {
        renderer.render(context, listOf(
            SettingGroup("", listOf(SettingItem.Toggle("flag", "Flag", true)))
        ), storage)
        assertTrue(storage.getBoolean("flag", false))
    }

    @Test fun `SingleSelect writes default to storage on first render`() {
        renderer.render(context, listOf(
            SettingGroup("", listOf(SettingItem.SingleSelect("sel", "Sel", listOf("A", "B", "C"), "B")))
        ), storage)
        assertEquals("B", storage.getString("sel", ""))
    }

    @Test fun `EditText writes default to storage on first render`() {
        renderer.render(context, listOf(
            SettingGroup("", listOf(SettingItem.EditText("addr", "Addr", "8.8.8.8")))
        ), storage)
        assertEquals("8.8.8.8", storage.getString("addr", ""))
    }

    @Test fun `subsequent render does not overwrite existing storage value`() {
        storage.putBoolean("flag", false)
        renderer.render(context, listOf(
            SettingGroup("", listOf(SettingItem.Toggle("flag", "Flag", true)))
        ), storage)
        // Default is true, but existing value is false — should not overwrite
        assertFalse(storage.getBoolean("flag", true))
    }
}
