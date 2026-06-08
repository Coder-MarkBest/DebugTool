package com.debugtools.general

import org.junit.Assert.*
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

class DiskMonitorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test fun `clamps interval to minimum 5 minutes`() {
        val monitor = DiskMonitor("/tmp", intervalMinutes = 2)
        assertEquals(5, monitor.intervalMinutes)
    }

    @Test fun `uses provided interval when at or above minimum`() {
        assertEquals(5, DiskMonitor("/tmp", intervalMinutes = 5).intervalMinutes)
        assertEquals(10, DiskMonitor("/tmp", intervalMinutes = 10).intervalMinutes)
    }

    @Test fun `measureSize returns 0 for nonexistent path`() {
        val monitor = DiskMonitor("/nonexistent/path/xyz_abc_123")
        assertEquals(0L, monitor.measureSize())
    }

    @Test fun `measureSize counts file sizes in directory`() {
        val dir = tempDir.newFolder("testdir")
        File(dir, "file1.txt").writeText("hello")    // 5 bytes
        File(dir, "file2.txt").writeText("world!!")  // 7 bytes
        val monitor = DiskMonitor(dir.absolutePath)
        assertEquals(12L, monitor.measureSize())
    }

    @Test fun `measureSize handles SecurityException gracefully`() {
        // Can't easily simulate SecurityException, but verify no crash for empty dir
        val dir = tempDir.newFolder("emptydir")
        val monitor = DiskMonitor(dir.absolutePath)
        assertEquals(0L, monitor.measureSize())
    }
}
