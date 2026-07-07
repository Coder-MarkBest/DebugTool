package com.debugtools.core.window

import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.window.view.DebugToolsTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugToolsThemeTest {
    @Test fun `theme maps overview statuses to semantic colors`() {
        assertEquals(DebugToolsTheme.danger, DebugToolsTheme.colorFor(OverviewStatus.ERROR))
        assertEquals(DebugToolsTheme.warning, DebugToolsTheme.colorFor(OverviewStatus.WARNING))
        assertEquals(DebugToolsTheme.success, DebugToolsTheme.colorFor(OverviewStatus.OK))
        assertEquals(DebugToolsTheme.secondaryText, DebugToolsTheme.colorFor(OverviewStatus.UNKNOWN))
    }
}
