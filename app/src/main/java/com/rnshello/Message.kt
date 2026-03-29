package com.rnshello
import java.text.SimpleDateFormat
import java.util.*

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val targetHash: String, // The conversation owner (Node A or Node B)
    val senderHash: String, // The actual sender (You or Them)
    val content: String,
    val timestamp: Long,
    val isImage: Boolean,
    val isSent: Boolean,
    var isDelivered: Boolean = false
) {
    fun getFormattedTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}