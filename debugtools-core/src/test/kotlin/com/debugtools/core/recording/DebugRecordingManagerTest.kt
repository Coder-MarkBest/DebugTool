package com.debugtools.core.recording

import android.content.Context
import android.view.View
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DebugRecordingManagerTest {
    @Test
    fun `start rejects nested recording`() {
        val manager = DebugRecordingManager(wallClock = { 1000L }, uptimeClock = { 10L })
        val root = Files.createTempDirectory("debugtools-recording").toFile()
        manager.start(emptyList(), root)

        val error = runCatching { manager.start(emptyList(), root) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test
    fun `stop survives module export failure`() {
        val manager = DebugRecordingManager(wallClock = { 1000L }, uptimeClock = { 10L })
        val root = Files.createTempDirectory("debugtools-recording").toFile()
        manager.start(listOf(FailingRecordableModule()), root)

        val report = manager.stop()

        assertEquals(1, report.moduleResults.size)
        assertTrue(report.issues.any { it.type == "MODULE_EXPORT_FAILED" })
        assertTrue(report.rootDir.exists())
    }

    private class FailingRecordableModule : DebugModule, RecordableModule {
        override val moduleId = "failing"
        override val tabTitle = "Failing"
        override val recorderId = "failing"

        override fun createContentView(context: Context): View = View(context)
        override fun buildSettings(): List<SettingGroup> = emptyList()
        override fun getBriefItems(): List<BriefItem> = emptyList()
        override fun onAttach(context: Context, storage: SettingsStorage) {}
        override fun onDetach() {}
        override fun onRecordingStart(context: RecordingContext) = ModuleRecordingSnapshot(moduleId)
        override fun onRecordingStop(context: RecordingContext): ModuleRecordingResult {
            error("boom")
        }
    }
}
