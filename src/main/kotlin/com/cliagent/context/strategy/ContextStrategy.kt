package com.cliagent.context.strategy

import com.cliagent.llm.model.ChatMessage

enum class ContextStrategyType {
    SLIDING_WINDOW,
    STICKY_FACTS,
    SUMMARY,
    BRANCHING
}

interface ContextStrategy {
    fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage>

    fun getName(): String

    fun getDescription(): String

    fun needsCompression(): Boolean = false

    suspend fun onAssistantResponse(assistantMessage: ChatMessage) {}

    fun reset() {}
}
