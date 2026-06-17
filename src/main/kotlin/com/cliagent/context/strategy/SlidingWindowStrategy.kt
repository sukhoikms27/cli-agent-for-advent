package com.cliagent.context.strategy

import com.cliagent.llm.model.ChatMessage

class SlidingWindowStrategy(
    private val windowSize: Int = 10
) : ContextStrategy {

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        val allMessages = history + newMessage
        val windowedMessages = allMessages.takeLast(windowSize)
        return listOf(systemPrompt) + windowedMessages
    }

    override fun getName(): String = "sliding"

    override fun getDescription(): String =
        "Keeps last $windowSize messages, discards older ones"

    override fun reset() {
        // No internal state
    }
}
