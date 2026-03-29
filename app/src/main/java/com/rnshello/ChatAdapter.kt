package com.rnshello

import android.graphics.BitmapFactory
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import android.content.Context

class ChatAdapter(private val context: Context, private val onImageClick: (String) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val messages = mutableListOf<Message>()
    private val messageMap = mutableMapOf<String, Int>()

    fun getMessages(): List<Message> = messages

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

    override fun getItemCount() = messages.size
    override fun getItemViewType(pos: Int) = when {
        messages[pos].isSent && !messages[pos].isImage -> 1
        !messages[pos].isSent && !messages[pos].isImage -> 2
        messages[pos].isSent && messages[pos].isImage -> 3
        else -> 4
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            1 -> SentVH(inflater.inflate(R.layout.item_chat_sent_text, parent, false))
            2 -> RecvVH(inflater.inflate(R.layout.item_chat_received_text, parent, false))
            3 -> SentImgVH(inflater.inflate(R.layout.item_chat_sent_image, parent, false))
            else -> RecvImgVH(inflater.inflate(R.layout.item_chat_received_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
        val m = messages[pos]
        val prefs = context.getSharedPreferences("rns_database", Context.MODE_PRIVATE)
        val nick = prefs.getString("nick_${m.senderHash}", "")
        val senderDisplay = if (!nick.isNullOrEmpty()) nick else m.senderHash.takeLast(6)

        when(holder) {
            is SentVH -> { holder.t.text = m.content; holder.time.text = m.getFormattedTime(); holder.tick.visibility = if(m.isDelivered) View.VISIBLE else View.GONE }
            is RecvVH -> { holder.name.text = senderDisplay; holder.t.text = m.content; holder.time.text = m.getFormattedTime() }
            is SentImgVH -> { 
                holder.img.setImageBitmap(BitmapFactory.decodeFile(m.content))
                holder.tick.visibility = if(m.isDelivered) View.VISIBLE else View.GONE
                holder.img.setOnClickListener { onImageClick(m.content) }
            }
            is RecvImgVH -> {
                holder.name.text = senderDisplay
                holder.img.setImageBitmap(BitmapFactory.decodeFile(m.content))
                holder.img.setOnClickListener { onImageClick(m.content) }
            }
        }
    }

    class SentVH(v: View): RecyclerView.ViewHolder(v) { val t=v.findViewById<TextView>(R.id.chatMessageText); val time=v.findViewById<TextView>(R.id.chatMessageTime); val tick=v.findViewById<TextView>(R.id.tickStatus) }
    class RecvVH(v: View): RecyclerView.ViewHolder(v) { val name=v.findViewById<TextView>(R.id.chatSenderName); val t=v.findViewById<TextView>(R.id.chatMessageText); val time=v.findViewById<TextView>(R.id.chatMessageTime) }
    class SentImgVH(v: View): RecyclerView.ViewHolder(v) { val img=v.findViewById<ImageView>(R.id.chatMessageImage); val tick=v.findViewById<TextView>(R.id.tickStatus); val time=v.findViewById<TextView>(R.id.chatMessageTime) }
    class RecvImgVH(v: View): RecyclerView.ViewHolder(v) { val name=v.findViewById<TextView>(R.id.chatSenderName); val img=v.findViewById<ImageView>(R.id.chatMessageImage); val time=v.findViewById<TextView>(R.id.chatMessageTime) }
}