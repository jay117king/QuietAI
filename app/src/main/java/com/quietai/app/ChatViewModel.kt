package com.quietai.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quietai.app.engine.LlmEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {
    // Added ID for stable DiffUtil performance
    data class ChatMessage(val id: Long = System.nanoTime(), val text: String, val fromUser: Boolean)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    var engine: LlmEngine? = null

    fun send(text: String) {
        if (text.isBlank()) return

        val userMsg = ChatMessage(text = text, fromUser = true)
        _messages.value = _messages.value + userMsg

        val currentEngine = engine
        if (currentEngine == null) {
            _messages.value = _messages.value + ChatMessage(text = "No engine selected.", fromUser = false)
            return
        }

        viewModelScope.launch {
            _busy.value = true
            try {
                // Limit context window to last 10 messages to prevent token overflow on local models
                val recentMessages = _messages.value.takeLast(10)
                val transcript = recentMessages.joinToString("\n") { msg ->
                    if (msg.fromUser) "User: ${msg.text}" else "Assistant: ${msg.text}"
                }
                val reply = currentEngine.generate(transcript)
                _messages.value = _messages.value + ChatMessage(text = reply, fromUser = false)
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage(text = "Error: ${e.message}", fromUser = false)
            } finally {
                _busy.value = false
            }
        }
    }
}
