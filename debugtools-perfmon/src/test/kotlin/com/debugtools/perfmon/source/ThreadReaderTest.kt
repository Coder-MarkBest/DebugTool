package com.debugtools.perfmon.source

import com.debugtools.perfmon.data.ThreadState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThreadReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeProcStat(totalJiffies: Long) {
        File(tmp.root, "stat").writeText(
            "cpu  ${totalJiffies / 4} 0 ${totalJiffies / 4} ${totalJiffies / 4} ${totalJiffies / 4} 0 0 0 0 0\n"
        )
    }

    private fun writeThread(pid: Int, tid: Int, name: String, state: Char, utime: Long, stime: Long) {
        val taskDir = File(tmp.root, "$pid/task/$tid").apply { mkdirs() }
        File(taskDir, "comm").writeText("$name\n")
        File(taskDir, "stat").writeText("$tid ($name) $state 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0")
    }

    @Test fun `countThreads counts task dir entries`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeThread(100, 100, "main", 'R', 0, 0)
        writeThread(100, 101, "worker", 'S', 0, 0)
        writeThread(100, 102, "gc", 'S', 0, 0)
        assertEquals(3, r.countThreads(100))
    }

    @Test fun `countThreads returns 0 for missing pid`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        assertEquals(0, r.countThreads(999))
    }

    @Test fun `readDetailed first sample primes baseline`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 50, 50)
        val list = r.readDetailed(100)
        assertEquals(1, list.size)
        assertEquals("main", list[0].name)
        assertEquals(0f, list[0].cpuPercent, 0.01f)
        assertEquals(ThreadState.RUNNING, list[0].state)
    }

    @Test fun `readDetailed second sample computes percent via delta`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 0, 0)
        r.readDetailed(100)
        // Second sample
        writeProcStat(4400)
        writeThread(100, 100, "main", 'R', 60, 40)
        val list = r.readDetailed(100)
        assertEquals(100f, list[0].cpuPercent, 0.5f)
    }

    @Test fun `state code maps to ThreadState enum`() {
        val r = ThreadReader(tmp.root, coreCount = 4)
        writeProcStat(4000)
        writeThread(100, 100, "a", 'R', 0, 0)
        writeThread(100, 101, "b", 'S', 0, 0)
        writeThread(100, 102, "c", 'D', 0, 0)
        writeThread(100, 103, "d", 'Z', 0, 0)
        val list = r.readDetailed(100)
        val byName = list.associateBy { it.name }
        assertEquals(ThreadState.RUNNING, byName["a"]!!.state)
        assertEquals(ThreadState.SLEEPING, byName["b"]!!.state)
        assertEquals(ThreadState.DISK_WAIT, byName["c"]!!.state)
        assertEquals(ThreadState.ZOMBIE, byName["d"]!!.state)
    }
}
