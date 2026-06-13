package com.debugtools.perfmon.data

/** Process / thread state code from /proc/<pid>/stat field 3. */
enum class ThreadState(val code: Char) {
    RUNNING('R'),
    SLEEPING('S'),
    DISK_WAIT('D'),
    ZOMBIE('Z'),
    STOPPED('T'),
    UNKNOWN('?');

    companion object {
        fun fromCode(c: Char): ThreadState =
            values().firstOrNull { it.code == c } ?: UNKNOWN
    }
}
