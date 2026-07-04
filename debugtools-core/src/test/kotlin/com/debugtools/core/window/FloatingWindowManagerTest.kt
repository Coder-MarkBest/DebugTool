package com.debugtools.core.window

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.debugtools.core.recording.DebugRecordingManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
class FloatingWindowManagerTest {
    private lateinit var context: Context
    private lateinit var modeManager: DisplayModeManager
    private lateinit var manager: FloatingWindowManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modeManager = DisplayModeManager()
        manager = FloatingWindowManager(context, modeManager, BriefOrientation.VERTICAL, DebugRecordingManager())
        ShadowSettings.setCanDrawOverlays(true)
    }

    private fun shadowWm(): ShadowWindowManagerImpl {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return Shadows.shadowOf(wm) as ShadowWindowManagerImpl
    }

    @Test fun `init adds view to WindowManager`() {
        manager.init(emptyList())
        assertTrue(shadowWm().views.isNotEmpty())
    }

    @Test fun `destroy removes view from WindowManager`() {
        manager.init(emptyList())
        manager.destroy()
        assertTrue(shadowWm().views.isEmpty())
    }

    @Test fun `init with no modules does not crash`() {
        try {
            manager.init(emptyList())
        } catch (e: Exception) {
            fail("init(emptyList()) threw an exception: ${e.message}")
        }
    }

    @Test fun `expanded width resizes and clamps to car console bounds`() {
        manager.init(emptyList())
        val initial = manager.expandedWidthForTest()

        manager.resizeExpandedBy(300)
        assertTrue(manager.expandedWidthForTest() > initial)

        manager.resizeExpandedBy(-10_000)
        assertEquals(manager.minExpandedWidthForTest(), manager.expandedWidthForTest())

        manager.resizeExpandedBy(10_000)
        assertEquals(manager.maxExpandedWidthForTest(), manager.expandedWidthForTest())
    }

    @Test fun `resize preserves minimized dimensions`() {
        manager.init(emptyList())
        modeManager.setMode(DisplayMode.MINIMIZED)
        val before = (shadowWm().views.first().layoutParams as WindowManager.LayoutParams).width

        manager.resizeExpandedBy(300)

        val after = (shadowWm().views.first().layoutParams as WindowManager.LayoutParams).width
        assertEquals(before, after)
        assertTrue(manager.expandedWidthForTest() > before)
    }
}
