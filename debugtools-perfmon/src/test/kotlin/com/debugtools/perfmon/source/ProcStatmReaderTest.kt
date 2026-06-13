package com.debugtools.perfmon.source

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcStatmReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writePidStatm(pid: Int, sizePages: Long, residentPages: Long) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        File(pidDir, "statm").writeText("$sizePages $residentPages 0 0 0 0 0\n")
    }

    @Test fun `reads resident pages and converts to bytes`() {
        val reader = ProcStatmReader(tmp.root, pageSize = 4096)
        writePidStatm(pid = 100, sizePages = 1000, residentPages = 256)
        val bytes = reader.readRssBytes(100)
        assertEquals(256L * 4096, bytes)
    }

    @Test fun `returns null for missing pid`() {
        val reader = ProcStatmReader(tmp.root, pageSize = 4096)
        assertNull(reader.readRssBytes(999))
    }
}
