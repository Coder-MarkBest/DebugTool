package com.debugtools.core.module

import androidx.annotation.ColorInt

data class BriefItem(
    val text: String,
    @ColorInt val color: Int? = null   // null = use default text color
)
