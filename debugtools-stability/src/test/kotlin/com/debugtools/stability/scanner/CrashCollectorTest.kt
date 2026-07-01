package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry
import com.debugtools.stability.protocol.CrashType
import org.junit.Assert.assertEquals
import org.junit.Test

class CrashCollectorTest {

    private fun entry(type: CrashType, proc: String, ts: Long, src: String = "DropBox:test", trace: String = "stack-$ts") =
        CrashEntry(type, proc, ts, src, trace, null)

    // ── filter by process ──

    @Test fun `filterByProcess keeps only matching names`() {
        val entries = listOf(
            entry(CrashType.JAVA_CRASH, "com.a", 100L),
            entry(CrashType.NATIVE_CRASH, "com.b", 200L),
            entry(CrashType.ANR, "com.a", 300L)
        )
        val result = CrashCollector.filterByProcess(entries, listOf("com.a"))
        assertEquals(2, result.size)
        assertEquals(listOf("com.a", "com.a"), result.map { it.processName })
    }

    @Test fun `filterByProcess with empty names returns all`() {
        val entries = listOf(entry(CrashType.JAVA_CRASH, "com.a", 100L))
        assertEquals(1, CrashCollector.filterByProcess(entries, emptyList()).size)
    }

    @Test fun `filterByProcess with empty entries returns empty`() {
        assertEquals(0, CrashCollector.filterByProcess(emptyList(), listOf("com.a")).size)
    }

    // ── merge & dedup ──

    @Test fun `merge de-duplicates by timestamp + processName + trace prefix 200`() {
        val trace = "Exception: boom\n".repeat(50) // long trace
        val e1 = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "DropBox:a", trace, null)
        val e2 = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "/data/anr/anr_1", trace, null)
        val e3 = CrashEntry(CrashType.JAVA_CRASH, "com.a", 200L, "DropBox:a", "different stack", null)
        val result = CrashCollector.merge(listOf(e1, e2), listOf(e3))
        assertEquals(2, result.size)
    }

    @Test fun `merge sorts by timestamp descending`() {
        val e1 = entry(CrashType.JAVA_CRASH, "com.a", 100L)
        val e2 = entry(CrashType.JAVA_CRASH, "com.a", 300L)
        val e3 = entry(CrashType.JAVA_CRASH, "com.a", 200L)
        val result = CrashCollector.merge(listOf(e1), listOf(e2, e3))
        assertEquals(listOf(300L, 200L, 100L), result.map { it.timestamp })
    }

    @Test fun `merge prefers DropBox source when duplicate`() {
        val trace = "same trace for dedup"
        val dropBox = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "DropBox:system_app_crash", trace, null)
        val fileSys = CrashEntry(CrashType.JAVA_CRASH, "com.a", 100L, "/data/anr/anr_1", trace, null)
        val result = CrashCollector.merge(listOf(dropBox), listOf(fileSys))
        assertEquals(1, result.size)
        assertEquals("DropBox:system_app_crash", result[0].sourcePath)
    }

    @Test fun `merge empty sources returns empty`() {
        assertEquals(0, CrashCollector.merge(emptyList(), emptyList()).size)
    }
}
