package com.debugtools.stability.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcessCheckerTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Write a fake /proc/<pid>/cmdline file with null-separated args. */
    private fun fakeProc(procDir: File, pid: Int, cmdline: String) {
        val dir = File(procDir, "$pid")
        dir.mkdirs()
        File(dir, "cmdline").writeBytes(cmdline.replace(" ", "\u0000").toByteArray())
    }

    private fun checker(procDir: File) = ProcessChecker(procDir)

    @Test fun `check returns true for matching process`() {
        fakeProc(tmp.root, 123, "com.debugtools.sample")
        fakeProc(tmp.root, 456, "com.android.phone")
        val status = checker(tmp.root).check(listOf("com.debugtools.sample"))
        assertEquals(mapOf("com.debugtools.sample" to true), status)
    }

    @Test fun `check returns false for missing process`() {
        val status = checker(tmp.root).check(listOf("com.nonexistent"))
        assertEquals(mapOf("com.nonexistent" to false), status)
    }

    @Test fun `check handles processes with arguments`() {
        fakeProc(tmp.root, 100, "com.xxx.voice --flag value")
        val status = checker(tmp.root).check(listOf("com.xxx.voice"))
        assertEquals(mapOf("com.xxx.voice" to true), status)
    }

    @Test fun `check returns multiple results`() {
        fakeProc(tmp.root, 1, "app.a")
        fakeProc(tmp.root, 2, "app.b")
        val status = checker(tmp.root).check(listOf("app.a", "app.b", "app.c"))
        assertEquals(
            mapOf("app.a" to true, "app.b" to true, "app.c" to false),
            status
        )
    }

    @Test fun `empty proc directory returns all false`() {
        val status = checker(tmp.root).check(listOf("com.a"))
        assertEquals(mapOf("com.a" to false), status)
    }
}
