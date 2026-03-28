package com.rnshello

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<Message>()

    fun addMessage(message: Message) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    override fun getItemCount(): Int = messages.size

    override fun getItemViewType(position: Int): Int {
        val message = messages[position]
        return when {
            message.isSent && !message.isImage -> VIEW_TYPE_SENT_TEXT
            !message.isSent && !message.isImage -> VIEW_TYPE_RECEIVED_TEXT
            message.isSent && message.isImage -> VIEW_TYPE_SENT_IMAGE
            else -> VIEW_TYPE_RECEIVED_IMAGE // !message.isSent && message.isImage
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SENT_TEXT -> SentTextViewHolder(inflater.inflate(R.layout.item_chat_sent_text, parent, false))
            VIEW_TYPE_RECEIVED_TEXT -> ReceivedTextViewHolder(inflater.inflate(R.layout.item_chat_received_text, parent, false))
            VIEW_TYPE_SENT_IMAGE -> SentImageViewHolder(inflater.inflate(R.layout.item_chat_sent_image, parent, false))
            else -> ReceivedImageViewHolder(inflater.inflate(R.layout.item_chat_received_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder.itemViewType) {
            VIEW_TYPE_SENT_TEXT -> (holder as SentTextViewHolder).bind(message)
            VIEW_TYPE_RECEIVED_TEXT -> (holder as ReceivedTextViewHolder).bind(message)
            VIEW_TYPE_SENT_IMAGE -> (holder as SentImageViewHolder).bind(message)
            VIEW_TYPE_RECEIVED_IMAGE -> (holder as ReceivedImageViewHolder).bind(message)
        }
    }

    companion object {
        const val VIEW_TYPE_SENT_TEXT = 1
        const val VIEW_TYPE_RECEIVED_TEXT = 2
        const val VIEW_TYPE_SENT_IMAGE = 3
        const val VIEW_TYPE_RECEIVED_IMAGE = 4
    }

    // --- ViewHolders ---

    class SentTextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.chatMessageText)
        private val messageTime: TextView = itemView.findViewById(R.id.chatMessageTime)
        fun bind(message: Message) {
            messageText.text = message.content
            messageTime.text = message.getFormattedTime()
        }
    }

    class ReceivedTextViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val senderName: TextView = itemView.findViewById(R.id.chatSenderName)
        private val messageText: TextView = itemView.findViewById(R.id.chatMessageText)
        private val messageTime: TextView = itemView.findViewById(R.id.chatMessageTime)
        fun bind(message: Message) {
            senderName.text = message.senderHash.take(6) // Shorten hash for display
            messageText.text = message.content
            messageTime.text = message.getFormattedTime()
        }
    }

    class SentImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageImage: ImageView = itemView.findViewById(R.id.chatMessageImage)
        private val messageTime: TextView = itemView.findViewById(R.id.chatMessageTime)
        fun bind(message: Message) {
            messageImage.setImageBitmap(BitmapFactory.decodeFile(message.content)) // content is imagePath
            messageTime.text = message.getFormattedTime()
        }
    }

    class ReceivedImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val senderName: TextView = itemView.findViewById(R.id.chatSenderName)
        private val messageImage: ImageView = itemView.findViewById(R.id.chatMessageImage)
        private val messageTime: TextView = itemView.findViewById(R.id.chatMessageTime)
        fun bind(message: Message) {
            senderName.text = message.senderHash.take(6)
            messageImage.setImageBitmap(BitmapFactory.decodeFile(message.content))
            messageTime.text = message.getFormattedTime()
        }
    }
}
