package com.debugtools.core.ipc.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DebugEvent(
    val timestamp: Long,
    val tag: String,
    val detail: String? = null
) : Parcelable
