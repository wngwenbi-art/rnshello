package com.rnshello

interface RnsCallback {
    fun onNewMessage(senderHash: String, content: String, timestamp: Long, isImage: Boolean, isSent: Boolean, id: String)
    fun onAnnounceReceived(hexAddress: String, nickname: String)
    fun onMessageDelivered(id: String)
}