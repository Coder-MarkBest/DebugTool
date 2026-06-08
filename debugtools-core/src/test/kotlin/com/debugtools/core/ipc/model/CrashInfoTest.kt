package com.debugtools.core.ipc.model

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashInfoTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Parcelable> roundTrip(original: T): T {
        val parcel = Parcel.obtain()
        original.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        val creator = original.javaClass.getField("CREATOR").get(null)
            as Parcelable.Creator<T>
        val restored = creator.createFromParcel(parcel)
        parcel.recycle()
        return restored
    }

    @Test fun `CrashInfo parcels correctly with message`() {
        val crash = CrashInfo(
            timestamp = 5000L,
            threadName = "main",
            exceptionClass = "NullPointerException",
            message = "object is null",
            stackTrace = "at com.example.Foo.bar(Foo.kt:42)"
        )
        val restored = roundTrip(crash)
        assertEquals(crash, restored)
    }

    @Test fun `CrashInfo with null message parcels correctly`() {
        val crash = CrashInfo(
            timestamp = 6000L,
            threadName = "io-thread",
            exceptionClass = "IllegalStateException",
            message = null,
            stackTrace = "at com.example.Bar.baz(Bar.kt:10)"
        )
        val restored = roundTrip(crash)
        assertNull(restored.message)
        assertEquals(crash, restored)
    }
}
