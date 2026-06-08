package com.debugtools.core.window

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
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
        manager = FloatingWindowManager(context, modeManager, BriefOrientation.VERTICAL)
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
}
