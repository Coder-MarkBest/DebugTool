package com.debugtools.general

interface GeneralView {
    fun showDiskSizes(sizes: List<Pair<String, Long>>)
    fun showProcessStates(states: List<Pair<String, Boolean>>)
}
