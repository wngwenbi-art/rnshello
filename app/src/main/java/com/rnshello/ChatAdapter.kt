package com.rnshello

import android.graphics.BitmapFactory
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(private val onImageClick: (String) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val messages = mutableListOf<Message>()
    private val messageMap = mutableMapOf<String, Int>() // To find messages for ticks

    fun addMessage(message: Message, lxmfId: String? = null) {
        messages.add(message)
        if (lxmfId != null) messageMap[lxmfId] = messages.size - 1
        notifyItemInserted(messages.size - 1)
    }

    fun markDelivered(lxmfId: String) {
        val index = messageMap[lxmfId]
        if (index != null) {
            messages[index].isDelivered = true
            notifyItemChanged(index)
        }
    }

    override fun getItemViewType(pos: Int) = when {
        messages[pos].isSent && !messages[pos].isImage -> 1
        !messages[pos].isSent && !messages[pos].isImage -> 2
        messages[pos].isSent && messages[pos].isImage -> 3
        else -> 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> SentTextVH(inflater.inflate(R.layout.item_chat_sent_text, parent, false))
            2 -> RecvTextVH(inflater.inflate(R.layout.item_chat_received_text, parent, false))
            3 -> SentImgVH(inflater.inflate(R.layout.item_chat_sent_image, parent, false))
            else -> RecvImgVH(inflater.inflate(R.layout.item_chat_received_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val m = messages[pos]
        when(holder) {
            is SentTextVH -> { holder.t.text = m.content; holder.time.text = m.getFormattedTime(); holder.tick.visibility = if(m.isDelivered) View.VISIBLE else View.GONE }
            is RecvTextVH -> { holder.t.text = m.content; holder.time.text = m.getFormattedTime() }
            is SentImgVH -> { 
                holder.img.setImageBitmap(BitmapFactory.decodeFile(m.content))
                holder.tick.visibility = if(m.isDelivered) View.VISIBLE else View.GONE
                holder.img.setOnClickListener { onImageClick(m.content) }
            }
            is RecvImgVH -> {
                holder.img.setImageBitmap(BitmapFactory.decodeFile(m.content))
                holder.img.setOnClickListener { onImageClick(m.content) }
            }
        }
    }

    override fun getItemCount() = messages.size

    class SentTextVH(v: View): RecyclerView.ViewHolder(v) { val t=v.findViewById<TextView>(R.id.chatMessageText); val time=v.findViewById<TextView>(R.id.chatMessageTime); val tick=v.findViewById<TextView>(R.id.tickStatus) }
    class RecvTextVH(v: View): RecyclerView.ViewHolder(v) { val t=v.findViewById<TextView>(R.id.chatMessageText); val time=v.findViewById<TextView>(R.id.chatMessageTime) }
    class SentImgVH(v: View): RecyclerView.ViewHolder(v) { val img=v.findViewById<ImageView>(R.id.chatMessageImage); val tick=v.findViewById<TextView>(R.id.tickStatus); val time=v.findViewById<TextView>(R.id.chatMessageTime) }
    class RecvImgVH(v: View): RecyclerView.ViewHolder(v) { val img=v.findViewById<ImageView>(R.id.chatMessageImage); val time=v.findViewById<TextView>(R.id.chatMessageTime) }
}