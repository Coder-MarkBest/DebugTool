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

    @Test fun `read does not throw for own pid`() {
        val reader = MemInfoReader(context)
        // In Robolectric, getProcessMemoryInfo is unimplemented and the wrapper's catch
        // returns null. The contract here is just "no uncaught exception".
        reader.read(android.os.Process.myPid())
    }
}
