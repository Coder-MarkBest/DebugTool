package com.debugtools.core.window

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import com.debugtools.core.window.view.ExpandedView
import com.debugtools.core.window.view.TabBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpandedViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `expanded view lays out left tab rail and right content area`() {
        val view = ExpandedView(context)
        view.setModules(listOf(FakeModule("network", "网络抓包"), FakeModule("perf", "性能监控")))

        assertEquals(LinearLayout.HORIZONTAL, view.orientation)
        assertEquals(2, view.childCount)
        assertTrue(view.getChildAt(0) is LinearLayout)
        assertTrue(view.getChildAt(1) is FrameLayout)

        val rail = view.getChildAt(0) as LinearLayout
        val content = view.getChildAt(1) as FrameLayout
        assertEquals(view.tabRailWidthPxForTest(), rail.layoutParams.width)
        assertEquals(LinearLayout.VERTICAL, rail.orientation)
        assertTrue(rail.getChildAt(0) is TabBarView)
        assertEquals(0, content.layoutParams.width)
        assertEquals(1f, (content.layoutParams as LinearLayout.LayoutParams).weight)
    }

    @Test fun `tab rail accepts resize drag callback`() {
        var totalDelta = 0
        val view = ExpandedView(context)
        view.onResizeDrag = { totalDelta += it }

        view.dispatchTabRailResizeDragForTest(48)

        assertEquals(48, totalDelta)
    }

    @Test fun `tab rail emits resize start drag and end callbacks`() {
        val events = mutableListOf<String>()
        val view = ExpandedView(context)
        view.onResizeStart = { events += "start" }
        view.onResizeDrag = { events += "drag:$it" }
        view.onResizeEnd = { events += "end" }

        view.dispatchTabRailResizeStartForTest()
        view.dispatchTabRailResizeDragForTest(24)
        view.dispatchTabRailResizeEndForTest()

        assertEquals(listOf("start", "drag:24", "end"), events)
    }

    @Test fun `vertical tab rail keeps tab controls compact when content grows`() {
        val view = ExpandedView(context)
        view.setModules(listOf(FakeModule("conversation", "对话链路")))

        val rail = view.getChildAt(0) as LinearLayout
        val tabBar = rail.getChildAt(0) as TabBarView
        val tab = tabBar.firstTabForTest()

        assertNotNull(tab)
        assertTrue(tab is TextView)
        val tabView = tab as TextView
        assertEquals(view.tabRailWidthPxForTest(), rail.layoutParams.width)
        assertTrue(tabView.layoutParams.width <= view.tabRailWidthPxForTest())
    }

    private class FakeModule(
        override val moduleId: String,
        override val tabTitle: String
    ) : DebugModule {
        override fun buildSettings(): List<SettingGroup> = emptyList()
        override fun createContentView(context: Context): View = TextView(context).apply { text = tabTitle }
        override fun getBriefItems(): List<BriefItem> = emptyList()
        override fun onAttach(context: Context, storage: SettingsStorage) = Unit
        override fun onDetach() = Unit
    }
}
