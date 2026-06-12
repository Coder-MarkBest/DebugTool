package com.debugtools.okhttp.data

data class WebSocketSession(
    val sessionId: String,
    val url: String,
    val handshakeRecordId: String,
    val openedAt: Long,
    var closedAt: Long? = null,
    var closeCode: Int? = null,
    var closeReason: String? = null,
    var failure: String? = null,
    val frames: MutableList<WebSocketFrame> = mutableListOf()
)
