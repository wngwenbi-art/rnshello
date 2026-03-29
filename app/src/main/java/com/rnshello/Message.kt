package com.rnshello

import java.text.SimpleDateFormat
import java.util.*

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val senderHash: String,
    val content: String,
    val timestamp: Long,
    val isImage: Boolean,
    val isSent: Boolean,
    var isDelivered: Boolean = false // This represents the "Single Tick"
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}