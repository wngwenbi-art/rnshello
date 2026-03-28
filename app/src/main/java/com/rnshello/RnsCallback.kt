package com.rnshello

interface RnsCallback {
    // We break the Message down into primitive types so Python (Chaquopy) can easily send it
    fun onNewMessage(senderHash: String, content: String, timestamp: Long, isImage: Boolean, isSent: Boolean)
    fun onAnnounceReceived(hexAddress: String)
}