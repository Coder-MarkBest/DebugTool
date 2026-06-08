package com.debugtools.core.ipc.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CrashInfo(
    val timestamp: Long,
    val threadName: String,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String
) : Parcelable
