package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ProcessTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcDiscovererTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeProcess(pid: Int, cmdline: String) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        // /proc/<pid>/cmdline uses null-byte separators; first segment is the process name.
        val bytes = cmdline.toByteArray() + 0x00.toByte()
        File(pidDir, "cmdline").writeBytes(bytes)
    }

    @Test fun `ByPid returns same pid when process exists`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.example")
        assertEquals(100, disco.resolve(ProcessTarget.ByPid(100)))
    }

    @Test fun `ByPid returns null when process gone`() {
        val disco = ProcDiscoverer(tmp.root)
        assertNull(disco.resolve(ProcessTarget.ByPid(999)))
    }

    @Test fun `ByName finds pid by matching first cmdline segment`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.other")
        writeProcess(pid = 200, cmdline = "com.example.target")
        writeProcess(pid = 300, cmdline = "another")
        assertEquals(200, disco.resolve(ProcessTarget.ByName("com.example.target")))
    }

    @Test fun `ByName returns null when no match`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.other")
        assertNull(disco.resolve(ProcessTarget.ByName("com.missing")))
    }

    @Test fun `ByName picks up new pid after restart`() {
        val disco = ProcDiscoverer(tmp.root)
        writeProcess(pid = 100, cmdline = "com.example")
        assertEquals(100, disco.resolve(ProcessTarget.ByName("com.example")))
        // Simulate restart: old pid gone, new pid
        File(tmp.root, "100").deleteRecursively()
        writeProcess(pid = 250, cmdline = "com.example")
        assertEquals(250, disco.resolve(ProcessTarget.ByName("com.example")))
    }
}
