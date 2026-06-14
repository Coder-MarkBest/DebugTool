package com.debugtools.perfmon.source

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcStatReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Write a fake /proc/<pid>/stat line with the given utime+stime values. */
    private fun writePidStat(pid: Int, utime: Long, stime: Long) {
        val pidDir = File(tmp.root, pid.toString()).apply { mkdirs() }
        // pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt
        // majflt cmajflt utime stime ...
        // Fields 1..13 = $pid (comm) S 1 1 1 0 -1 0 0 0 0 0
        val fields = "$pid (test) S 1 1 1 0 -1 0 0 0 0 0 $utime $stime 0 0"
        File(pidDir, "stat").writeText(fields)
    }

    /** Write a fake /proc/stat with given total CPU jiffies. */
    private fun writeProcStat(totalJiffies: Long) {
        // cpu user nice system idle iowait irq softirq steal guest guest_nice
        File(tmp.root, "stat").writeText("cpu  ${totalJiffies / 4} 0 ${totalJiffies / 4} ${totalJiffies / 4} ${totalJiffies / 4} 0 0 0 0 0\n")
    }

    @Test fun `first read returns null (no baseline yet)`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)
        writePidStat(pid = 100, utime = 50, stime = 50)
        writeProcStat(totalJiffies = 4000)
        assertNull(reader.read(100))
    }

    @Test fun `second read computes percent using delta`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)

        writePidStat(pid = 100, utime = 0, stime = 0)
        writeProcStat(totalJiffies = 4000)
        reader.read(100)  // primes baseline

        // 100 jiffies of process CPU vs 400 jiffies system delta on 4 cores
        // → fraction 0.25, * 100 * 4 = 100% (top-style: one full core)
        writePidStat(pid = 100, utime = 60, stime = 40)
        writeProcStat(totalJiffies = 4400)
        val pct = reader.read(100)
        assertNotNull(pct)
        assertEquals(100f, pct!!, 0.5f)
    }

    @Test fun `read returns null for missing pid`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)
        writeProcStat(totalJiffies = 4000)
        assertNull(reader.read(999))
    }

    @Test fun `reading 200 percent for two-core full load`() {
        val reader = ProcStatReader(tmp.root, coreCount = 4)
        writePidStat(100, 0, 0); writeProcStat(4000); reader.read(100)
        // 200 jiffies process delta, 400 system delta, 4 cores → 200%
        writePidStat(100, 100, 100); writeProcStat(4400)
        val pct = reader.read(100)
        assertEquals(200f, pct!!, 0.5f)
    }

    @Test fun `falls back to wall clock when proc stat is unreadable`() {
        // Simulate Android non-system app: /proc/stat denied by SELinux → readTotalJiffies returns null.
        // Reader must fall back to wall-clock denominator instead of returning null.
        var nowNanos = 1_000_000_000L
        val reader = ProcStatReader(tmp.root, coreCount = 4, clockNanos = { nowNanos })

        // No writeProcStat() — /proc/stat absent simulates SELinux denial.
        writePidStat(pid = 100, utime = 0, stime = 0)
        assertNull(reader.read(100))  // first sample primes baseline

        // Advance wall clock 1.0s; process accumulated 100 jiffies (1.0s @ USER_HZ=100)
        // → one full core busy for the whole second → 100%.
        nowNanos += 1_000_000_000L
        writePidStat(pid = 100, utime = 60, stime = 40)
        val pct = reader.read(100)
        assertNotNull("expected non-null fallback CPU%", pct)
        assertEquals(100f, pct!!, 1f)
    }
}
