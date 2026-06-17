package com.cliagent.context.strategy

import com.cliagent.context.HistoryCompressor
import com.cliagent.llm.model.ChatMessage
import com.cliagent.memory.MemoryStore

class SummaryStrategy(
    private val historyCompressor: HistoryCompressor,
    private val memoryStore: MemoryStore,
    private val chatId: String
) : ContextStrategy {

    @Volatile
    private var summaryCleared = false

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        return listOf(systemPrompt) + history + newMessage
    }

    override fun getName(): String = "summary"

    override fun getDescription(): String =
        "Auto-summarizes old messages via LLM, keeps recent as-is"

    override fun needsCompression(): Boolean = true

    override fun reset() {
        summaryCleared = true
    }

    suspend fun clearSummaryIfRequested() {
        if (summaryCleared) {
            memoryStore.clearSummary(chatId)
            summaryCleared = false
        }
    }
}
