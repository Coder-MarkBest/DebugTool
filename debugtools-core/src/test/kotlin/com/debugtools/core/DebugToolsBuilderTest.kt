package com.debugtools.core

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.persistence.SharedPreferencesStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.core.window.BriefOrientation
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugToolsBuilderTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun `builder default processMode is ATTACHED`() {
        val builder = DebugToolsBuilder(context)
        assertEquals(ProcessMode.ATTACHED, builder.processMode)
    }

    @Test fun `builder stores custom processMode`() {
        val builder = DebugToolsBuilder(context).processMode(ProcessMode.INDEPENDENT)
        assertEquals(ProcessMode.INDEPENDENT, builder.processMode)
    }

    @Test fun `builder stores custom storage`() {
        val storage = SharedPreferencesStorage(context, "test")
        val builder = DebugToolsBuilder(context).storage(storage)
        assertEquals(storage, builder.storage)
    }

    @Test fun `builder register adds module`() {
        val module = fakeModule("m1")
        val builder = DebugToolsBuilder(context).register(module)
        assertEquals(1, builder.modules.size)
        assertEquals("m1", builder.modules[0].moduleId)
    }

    @Test fun `builder briefOrientation defaults to VERTICAL`() {
        val builder = DebugToolsBuilder(context)
        assertEquals(BriefOrientation.VERTICAL, builder.briefOrientation)
    }

    @Test fun `builder briefOrientation can be set`() {
        val builder = DebugToolsBuilder(context).briefOrientation(BriefOrientation.HORIZONTAL)
        assertEquals(BriefOrientation.HORIZONTAL, builder.briefOrientation)
    }

    private fun fakeModule(id: String) = object : DebugModule {
        override val moduleId = id
        override val tabTitle = id
        override fun buildSettings() = emptyList<SettingGroup>()
        override fun createContentView(ctx: Context) = View(ctx)
        override fun getBriefItems() = emptyList<BriefItem>()
        override fun onAttach(ctx: Context, storage: SettingsStorage) {}
        override fun onDetach() {}
    }
}
