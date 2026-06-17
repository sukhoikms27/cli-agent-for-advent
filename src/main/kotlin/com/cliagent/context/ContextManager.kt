package com.cliagent.context

import com.cliagent.context.strategy.ContextStrategy
import com.cliagent.llm.model.ChatMessage

class ContextManager(
    private var strategy: ContextStrategy
) {
    fun getStrategy(): ContextStrategy = strategy

    fun switchStrategy(newStrategy: ContextStrategy): String {
        val oldName = strategy.getName()
        strategy.reset()
        strategy = newStrategy
        return "Switched from $oldName to ${newStrategy.getName()}"
    }

    fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        return strategy.buildMessages(history, newMessage, systemPrompt)
    }

    suspend fun onAssistantResponse(assistantMessage: ChatMessage) {
        strategy.onAssistantResponse(assistantMessage)
    }

    fun needsCompression(): Boolean = strategy.needsCompression()

    fun reset() {
        strategy.reset()
    }
}
