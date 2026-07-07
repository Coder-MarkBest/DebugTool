package com.debugtools.general

import com.debugtools.core.overview.OverviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityModuleOverviewTest {
    @Test
    fun emptyOverviewUsesAvailabilityCopy() {
        val item = AvailabilityModule.overviewItem(emptyList())

        assertEquals("debugtools_availability", item.moduleId)
        assertEquals("可用性", item.title)
        assertEquals(OverviewStatus.UNKNOWN, item.status)
        assertEquals("暂无可用性数据", item.primaryText)
    }

    @Test
    fun overviewPrioritizesUnavailableItems() {
        val item = AvailabilityModule.overviewItem(
            listOf(
                AvailabilityItem("network", "网络", AvailabilityStatus.AVAILABLE),
                AvailabilityItem("privacy", "隐私协议", AvailabilityStatus.UNAVAILABLE, "未同意"),
                AvailabilityItem("tts", "系统音量", AvailabilityStatus.DEGRADED, "音量偏低")
            )
        )

        assertEquals(OverviewStatus.ERROR, item.status)
        assertEquals("1不可用 · 1降级 · 0未知", item.primaryText)
        assertEquals("隐私协议", item.metrics.first().label)
        assertEquals("未同意", item.metrics.first().value)
    }

    @Test
    fun overviewReportsDegradedWhenThereIsNoUnavailableItem() {
        val item = AvailabilityModule.overviewItem(
            listOf(
                AvailabilityItem("nlu", "外部引擎", AvailabilityStatus.DEGRADED, "响应慢"),
                AvailabilityItem("network", "网络", AvailabilityStatus.AVAILABLE)
            )
        )

        assertEquals(OverviewStatus.WARNING, item.status)
        assertEquals("0不可用 · 1降级 · 0未知", item.primaryText)
    }

    @Test
    fun externalSourceItemsAreIncludedInSnapshot() {
        val source = AvailabilityItemSource {
            listOf(AvailabilityItem("external", "外部条件", AvailabilityStatus.UNAVAILABLE, "未满足"))
        }
        val module = AvailabilityModule.builder()
            .addExternalSource(source)
            .build()

        val item = module.getOverviewItems().single()

        assertEquals(OverviewStatus.ERROR, item.status)
        assertTrue(item.primaryText.contains("1不可用"))
    }
}
