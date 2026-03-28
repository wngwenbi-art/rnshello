package com.rnshello

interface RnsCallback {
    fun onNewMessage(message: Message)
    fun onAnnounceReceived(hexAddress: String)
}
