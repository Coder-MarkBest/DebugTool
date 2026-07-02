package com.debugtools.stability.scanner

import com.debugtools.stability.protocol.CrashEntry

/** Abstraction over different crash data sources. */
interface CrashSource {
    /** Read all crash entries from this source. Non-null, returns empty list on failure. */
    fun readAll(): List<CrashEntry>
}
