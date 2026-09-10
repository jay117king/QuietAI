package com.quietai.app

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.quietai.app.databinding.ItemMessageBinding

class ChatAdapter : ListAdapter<ChatViewModel.ChatMessage, ChatAdapter.MsgViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<ChatViewModel.ChatMessage>() {
        // FIX: Use ID for item comparison to prevent full list rebinds
        override fun areItemsTheSame(a: ChatViewModel.ChatMessage, b: ChatViewModel.ChatMessage) = a.id == b.id
        override fun areContentsTheSame(a: ChatViewModel.ChatMessage, b: ChatViewModel.ChatMessage) = a == b
    }

    class MsgViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return MsgViewHolder(ItemMessageBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
        val msg = getItem(position)
        holder.binding.messageText.text = msg.text
        
        val lp = holder.binding.messageText.layoutParams as FrameLayout.LayoutParams
        if (msg.fromUser) {
            lp.gravity = Gravity.END
            holder.binding.messageText.setBackgroundResource(R.drawable.bg_bubble_user)
        } else {
            lp.gravity = Gravity.START
            holder.binding.messageText.setBackgroundResource(R.drawable.bg_bubble_ai)
        }
        holder.binding.messageText.layoutParams = lp
    }
}
