package com.debugtools.core.overview

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.debugtools.core.module.BriefItem
import com.debugtools.core.module.DebugModule
import com.debugtools.core.persistence.SettingsStorage
import com.debugtools.core.settings.SettingGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OverviewAggregatorTest {
    @Test fun `collect returns items from overview providers only sorted by severity`() {
        val modules = listOf(
            FakeModule("plain", "普通"),
            ProviderModule(OverviewItem("ok", "正常", OverviewStatus.OK, "正常")),
            ProviderModule(OverviewItem("err", "错误", OverviewStatus.ERROR, "失败")),
            ProviderModule(OverviewItem("warn", "警告", OverviewStatus.WARNING, "慢")),
            ProviderModule(OverviewItem("recording", "录制", OverviewStatus.RECORDING, "录制中")),
            ProviderModule(OverviewItem("unknown", "未知", OverviewStatus.UNKNOWN, "暂无数据"))
        )

        val items = OverviewAggregator.collect(modules)

        assertEquals(listOf("err", "warn", "recording", "unknown", "ok"), items.map { it.moduleId })
    }

    @Test fun `collect keeps multiple items from one provider`() {
        val modules = listOf(
            MultiProviderModule(
                listOf(
                    OverviewItem("startup", "启动链路", OverviewStatus.OK, "正常"),
                    OverviewItem("conversation", "对话链路", OverviewStatus.ERROR, "失败")
                )
            )
        )

        val items = OverviewAggregator.collect(modules)

        assertEquals(listOf("conversation", "startup"), items.map { it.moduleId })
    }

    private open class FakeModule(
        override val moduleId: String,
        override val tabTitle: String
    ) : DebugModule {
        override fun buildSettings(): List<SettingGroup> = emptyList()
        override fun createContentView(context: Context): View =
            TextView(context).apply { text = tabTitle }
        override fun getBriefItems(): List<BriefItem> = emptyList()
        override fun onAttach(context: Context, storage: SettingsStorage) = Unit
        override fun onDetach() = Unit
    }

    private class ProviderModule(
        private val item: OverviewItem
    ) : FakeModule(item.moduleId, item.title), OverviewProvider {
        override fun getOverviewItems(): List<OverviewItem> = listOf(item)
    }

    private class MultiProviderModule(
        private val items: List<OverviewItem>
    ) : FakeModule("multi", "多项"), OverviewProvider {
        override fun getOverviewItems(): List<OverviewItem> = items
    }
}
