package com.debugtools.core.window

import android.content.Context
import android.view.Gravity
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

    @Test fun `expanded resize keeps window anchored to screen right edge`() {
        manager.init(emptyList())
        val displayWidth = context.resources.displayMetrics.widthPixels

        manager.resizeExpandedBy(300)

        val params = shadowWm().views.first().layoutParams as WindowManager.LayoutParams
        assertEquals(Gravity.TOP or Gravity.START, params.gravity)
        assertEquals(displayWidth, params.x + params.width)
    }

    @Test fun `expanded window only covers visible panel when not dragging`() {
        manager.init(emptyList())
        manager.resizeExpandedBy(300)

        manager.endExpandedResizeDragForTest()

        val params = shadowWm().views.first().layoutParams as WindowManager.LayoutParams
        assertEquals(manager.expandedWidthForTest(), params.width)
        assertEquals(context.resources.displayMetrics.widthPixels, params.x + params.width)
    }

    @Test fun `expanded resize start expands touch window to max width`() {
        manager.init(emptyList())
        val displayWidth = context.resources.displayMetrics.widthPixels

        manager.beginExpandedResizeDragForTest()

        val during = shadowWm().views.first().layoutParams as WindowManager.LayoutParams
        assertEquals(manager.maxExpandedWidthForTest(), during.width)
        assertEquals(displayWidth, during.x + during.width)
    }

    @Test fun `expanded resize end shrinks touch window to final visible width`() {
        manager.init(emptyList())
        manager.beginExpandedResizeDragForTest()
        manager.resizeExpandedBy(300)

        manager.endExpandedResizeDragForTest()

        val params = shadowWm().views.first().layoutParams as WindowManager.LayoutParams
        assertEquals(manager.expandedWidthForTest(), params.width)
        assertEquals(context.resources.displayMetrics.widthPixels, params.x + params.width)
    }

    @Test fun `expanded drag keeps max touch window stable until release`() {
        manager.init(emptyList())

        manager.beginExpandedResizeDragForTest()
        val before = shadowWm().views.first().layoutParams as WindowManager.LayoutParams
        val beforeWidth = before.width
        val beforeX = before.x

        manager.resizeExpandedBy(300)

        val during = shadowWm().views.first().layoutParams as WindowManager.LayoutParams
        assertEquals(beforeWidth, during.width)
        assertEquals(beforeX, during.x)
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
