package com.debugtools.core.module

import android.content.Context
import android.view.View
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import org.junit.Assert.*
import org.junit.Test

class ModuleRegistryTest {

    private fun fakeModule(id: String) = object : DebugModule {
        override val moduleId = id
        override val tabTitle = id
        override fun buildSettings() = emptyList<SettingGroup>()
        override fun createContentView(context: Context) = View(context)
        override fun getBriefItems() = emptyList<BriefItem>()
        override fun onAttach(context: Context, storage: SettingsStorage) {}
        override fun onDetach() {}
    }

    @Test fun `register adds module`() {
        val registry = ModuleRegistry()
        registry.register(fakeModule("a"))
        assertEquals(1, registry.modules.size)
    }

    @Test fun `register throws on duplicate moduleId`() {
        val registry = ModuleRegistry()
        registry.register(fakeModule("a"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(fakeModule("a"))
        }
    }

    @Test fun `modules preserves insertion order`() {
        val registry = ModuleRegistry()
        listOf("c", "a", "b").forEach { registry.register(fakeModule(it)) }
        assertEquals(listOf("c", "a", "b"), registry.modules.map { it.moduleId })
    }

    @Test fun `empty registry has no modules`() {
        val registry = ModuleRegistry()
        assertTrue(registry.modules.isEmpty())
    }
}
