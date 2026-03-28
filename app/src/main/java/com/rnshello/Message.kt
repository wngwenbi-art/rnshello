package com.rnshello

import java.text.SimpleDateFormat
import java.util.*

data class Message(
    val senderHash: String,
    val content: String,
    val timestamp: Long,
    val isImage: Boolean,
    val isSent: Boolean // True if sent by us, False if received
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
