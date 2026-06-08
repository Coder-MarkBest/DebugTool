package com.debugtools.network.model

enum class NetworkQuality {
    EXCELLENT, GOOD, POOR, OFFLINE;

    companion object {
        fun from(type: NetworkType, pingMs: Int?): NetworkQuality = when {
            type == NetworkType.NONE || pingMs == null -> OFFLINE
            pingMs < 50  -> EXCELLENT
            pingMs < 150 -> GOOD
            else         -> POOR
        }
    }
}
