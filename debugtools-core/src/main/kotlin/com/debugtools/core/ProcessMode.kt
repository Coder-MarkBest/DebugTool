package com.debugtools.core

enum class ProcessMode {
    /** Debug tool runs in a separate :debug process. Main process communicates via AIDL. */
    INDEPENDENT,
    /** Debug tool runs in the main app process. No IPC overhead. */
    ATTACHED
}
