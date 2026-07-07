package com.debugtools.core.window

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.debugtools.core.overview.OverviewItem
import com.debugtools.core.overview.OverviewStatus
import com.debugtools.core.window.view.DebugToolsTheme
import com.debugtools.core.window.view.OverviewView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OverviewViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `clicking overview row reports module id`() {
        val clicked = mutableListOf<String>()
        val view = OverviewView(context)
        view.update(
            listOf(OverviewItem("conversation", "对话链路", OverviewStatus.OK, "正常")),
            onModuleClick = { clicked += it }
        )

        view.performRowClickForTest("conversation")

        assertEquals(listOf("conversation"), clicked)
    }

    @Test fun `overview rows keep stable compact height`() {
        val view = OverviewView(context)
        view.update(
            listOf(OverviewItem("startup", "启动链路", OverviewStatus.WARNING, "慢步骤 1")),
            onModuleClick = {}
        )

        assertEquals(
            DebugToolsTheme.dp(context.resources, DebugToolsTheme.rowMinHeightDp),
            view.rowMinHeightForTest("startup")
        )
    }
}
