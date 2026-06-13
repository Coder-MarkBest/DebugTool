package com.debugtools.perfmon.sampler

import com.debugtools.perfmon.Config
import com.debugtools.perfmon.data.ProcessTarget
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.ProcDiscoverer
import com.debugtools.perfmon.source.ProcStatReader
import com.debugtools.perfmon.source.ProcStatmReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class Tier1SamplerTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeProcStat(total: Long) {
        File(tmp.root, "stat").writeText(
            "cpu  ${total / 4} 0 ${total / 4} ${total / 4} ${total / 4} 0 0 0 0 0\n"
        )
    }

    private fun writeProcess(pid: Int, name: String, utime: Long, stime: Long, rssPages: Long) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        File(pidDir, "cmdline").writeBytes(name.toByteArray() + 0x00.toByte())
        File(pidDir, "stat").writeText("$pid ($name) S 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0")
        File(pidDir, "statm").writeText("1000 $rssPages 0 0 0 0 0\n")
        File(pidDir, "task/$pid").mkdirs()  // one thread
    }

    @Test fun `samples each target every interval and emits to repository`() = runTest {
        writeProcStat(4000)
        writeProcess(100, "com.example", 0, 0, 256)

        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier1Sampler(
            targets = listOf(ProcessTarget.ByName("com.example")),
            repository = repo,
            config = Config(updateIntervalSec = 10),
            discoverer = ProcDiscoverer(tmp.root),
            statReader = ProcStatReader(tmp.root, coreCount = 4),
            statmReader = ProcStatmReader(tmp.root, pageSize = 4096),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )

        sampler.start(this)
        advanceTimeBy(50L)  // first sample on start

        // Update fake /proc and advance one interval
        writeProcStat(4400)
        writeProcess(100, "com.example", 60, 40, 256)
        advanceTimeBy(10_001L)

        val snap = repo.state.value.series["name:com.example"]!!.snapshot()
        assertTrue("expected at least 2 samples, got ${snap.size}", snap.size >= 2)
        // Last sample should have CPU% ~100 (one full core)
        val last = snap.last().value
        assertEquals(100f, last.cpuPercent, 1f)
        assertEquals(256L * 4096, last.rssBytes)
        assertTrue(last.alive)

        sampler.stop()
    }

    @Test fun `samples missing process as alive=false`() = runTest {
        writeProcStat(4000)
        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier1Sampler(
            targets = listOf(ProcessTarget.ByName("com.absent")),
            repository = repo,
            config = Config(updateIntervalSec = 10),
            discoverer = ProcDiscoverer(tmp.root),
            statReader = ProcStatReader(tmp.root, coreCount = 4),
            statmReader = ProcStatmReader(tmp.root, pageSize = 4096),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )
        sampler.start(this)
        advanceTimeBy(50L)
        val snap = repo.state.value.series["name:com.absent"]!!.snapshot()
        assertEquals(1, snap.size)
        assertFalse(snap[0].value.alive)
        assertNull(snap[0].value.pid)
        sampler.stop()
    }
}
