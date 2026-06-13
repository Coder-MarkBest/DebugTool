package com.debugtools.perfmon.sampler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.debugtools.perfmon.Config
import com.debugtools.perfmon.repository.PerfRepository
import com.debugtools.perfmon.source.MemInfoReader
import com.debugtools.perfmon.source.ThreadReader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class Tier2SamplerTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun writeProcStat(total: Long) {
        File(tmp.root, "stat").writeText(
            "cpu  ${total / 4} 0 ${total / 4} ${total / 4} ${total / 4} 0 0 0 0 0\n"
        )
    }

    private fun writeThread(pid: Int, tid: Int, name: String, state: Char, utime: Long, stime: Long) {
        val taskDir = File(tmp.root, "$pid/task/$tid").apply { mkdirs() }
        File(taskDir, "comm").writeText("$name\n")
        File(taskDir, "stat").writeText("$tid ($name) $state 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0")
    }

    @Test fun `selectPid emits detail on next interval`() = runTest {
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 0, 0)
        writeThread(100, 101, "worker", 'S', 0, 0)

        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier2Sampler(
            repository = repo,
            config = Config(updateIntervalSec = 10),
            memReader = MemInfoReader(context),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )
        sampler.start(this)
        sampler.selectPid(100)
        advanceTimeBy(50L)

        val d = repo.state.value.detail
        assertNotNull("expected detail after selectPid", d)
        assertEquals(100, d!!.pid)
        assertEquals(2, d.threads.size)

        sampler.stop()
    }

    @Test fun `selectPid null clears detail`() = runTest {
        writeProcStat(4000)
        writeThread(100, 100, "main", 'R', 0, 0)
        val repo = PerfRepository(Config(updateIntervalSec = 10))
        val sampler = Tier2Sampler(
            repository = repo,
            config = Config(updateIntervalSec = 10),
            memReader = MemInfoReader(context),
            threadReader = ThreadReader(tmp.root, coreCount = 4)
        )
        sampler.start(this)
        sampler.selectPid(100)
        advanceTimeBy(50L)
        assertNotNull(repo.state.value.detail)

        sampler.selectPid(null)
        advanceTimeBy(50L)
        assertNull(repo.state.value.detail)
        sampler.stop()
    }
}
