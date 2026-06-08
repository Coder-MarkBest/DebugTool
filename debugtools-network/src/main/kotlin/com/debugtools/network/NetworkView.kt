package com.debugtools.network

import androidx.annotation.ColorInt

interface NetworkView {
    fun showNetworkState(text: String, @ColorInt color: Int)
}
