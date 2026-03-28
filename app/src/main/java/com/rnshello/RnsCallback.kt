package com.rnshello

interface RnsCallback {
    fun onTextReceived(senderHash: String, text: String)
    fun onImageReceived(senderHash: String, imagePath: String)
    fun onAnnounceReceived(hexAddress: String)
}