package com.debugtools.core.ipc.model

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugEventTest {

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

    @Test fun `DebugEvent parcels correctly with detail`() {
        val event = DebugEvent(timestamp = 1000L, tag = "ASR开始", detail = "sessionId=abc")
        val restored = roundTrip(event)
        assertEquals(event, restored)
    }

    @Test fun `DebugEvent with null detail parcels correctly`() {
        val event = DebugEvent(timestamp = 2000L, tag = "NLU结束", detail = null)
        val restored = roundTrip(event)
        assertNull(restored.detail)
        assertEquals(event, restored)
    }
}
