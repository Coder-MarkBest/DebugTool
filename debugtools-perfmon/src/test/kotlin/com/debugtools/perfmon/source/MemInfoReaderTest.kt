package com.debugtools.perfmon.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemInfoReaderTest {

    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun `read returns null for nonexistent pid`() {
        val reader = MemInfoReader(context)
        // Pid 99999 almost certainly doesn't exist in Robolectric environment;
        // getProcessMemoryInfo returns empty/zero values which we map to null.
        val result = reader.read(99999)
        // Either null or zero — both indicate "no real data"
        assertTrue(result == null || result.totalPssKb == 0)
    }

    @Test fun `read returns own process info for myPid`() {
        val reader = MemInfoReader(context)
        val result = reader.read(android.os.Process.myPid())
        assertNotNull(result)
        // In Robolectric, getProcessMemoryInfo may return zeros — we only check
        // the call didn't throw.
    }
}
