package com.cliagent.agent

import com.cliagent.context.ContextManager
import com.cliagent.context.HistoryCompressor
import com.cliagent.context.strategy.BranchingStrategy
import com.cliagent.context.strategy.ContextStrategyType
import com.cliagent.context.strategy.StickyFactsStrategy
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.PromptTemplates
import com.cliagent.llm.model.ReasoningStrategy
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.llm.token.TokenCounter
import com.cliagent.memory.MemoryStore

class ContextAwareAgent(
    private val llmClient: LlmClient,
    private val memoryStore: MemoryStore,
    private val model: String,
    private val chatId: String,
    private val systemPrompt: ChatMessage = SystemPrompts.default,
    private val reasoningStrategy: ReasoningStrategy? = null,
    private val tokenCounter: TokenCounter = TokenCounter(),
    private val contextLimit: Int = 128000,
    private val historyCompressor: HistoryCompressor? = null,
    private val contextManager: ContextManager? = null
) : Agent {

    private var history = mutableListOf<ChatMessage>()
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (!loaded) {
            history = memoryStore.loadHistory(chatId).toMutableList()
            loaded = true
            // Load strategy-specific state
            (contextManager?.getStrategy() as? StickyFactsStrategy)?.let {
                it.setFacts(memoryStore.loadFacts(chatId))
            }
            (contextManager?.getStrategy() as? BranchingStrategy)?.let {
                it.loadBranches()
            }
        }
    }

    override suspend fun chat(userMessage: String): String {
        ensureLoaded()

        val lastMsgId = history.lastOrNull()?.id
        val userMsg = ChatMessage(
            role = "user",
            content = userMessage,
            parentId = lastMsgId
        )
        history.add(userMsg)
        memoryStore.saveMessage(chatId, userMsg)

        // Auto-compression (if no contextManager and compressor is set)
        if (contextManager == null && historyCompressor != null) {
            val shouldCompress = history.size > historyCompressor.compressThreshold &&
                history.size % historyCompressor.compressThreshold == 0
            if (shouldCompress) {
                println("🔄 Compressing history...")
                val existingSummary = memoryStore.loadSummary(chatId)
                val result = historyCompressor.compress(history, existingSummary)
                if (result.wasCompressed && result.summary != null) {
                    memoryStore.saveSummary(chatId, result.summary)
                    println("✓ Compressed ${result.summarizedCount} messages (~${result.tokenEstimate} tokens in summary)")
                }
            }
        }

        // Build messages
        val messagesToSend = buildMessagesToSend(userMsg)
        val estimatedTokens = tokenCounter.estimateHistoryTokens(messagesToSend)
        if (estimatedTokens > contextLimit) {
            println("⚠️ Warning: estimated $estimatedTokens tokens exceeds context limit ($contextLimit)")
        }

        val request = ChatRequest(model = model, messages = messagesToSend)
        val result = llmClient.chat(request)

        return when (result) {
            is LlmResult.Success -> {
                val usage = result.data.usage
                tokenCounter.recordUsage(chatId, usage)

                val assistantContent = result.data.choices.first().message.content
                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = assistantContent,
                    parentId = userMsg.id
                )
                history.add(assistantMsg)
                memoryStore.saveMessage(chatId, assistantMsg)

                // Let strategy process the response (e.g., update facts)
                contextManager?.onAssistantResponse(assistantMsg)
                // Persist facts after strategy update
                (contextManager?.getStrategy() as? StickyFactsStrategy)?.let {
                    memoryStore.saveFacts(chatId, it.getFacts())
                }

                assistantContent
            }
            is LlmResult.Error -> "Error: ${result.code} — ${result.message}"
        }
    }

    private suspend fun buildMessagesToSend(userMsg: ChatMessage): List<ChatMessage> {
        val system = if (reasoningStrategy != null) {
            PromptTemplates.buildSystemMessage(reasoningStrategy)
        } else {
            systemPrompt
        }

        // If contextManager is set, delegate to strategy
        if (contextManager != null) {
            val summary = memoryStore.loadSummary(chatId)
            val summaryMessage = summary?.let {
                ChatMessage(role = "system", content = "[Previous conversation summary]\n$it")
            }
            // Strategy builds messages; summary is added if present
            val strategyMessages = contextManager.buildMessages(history, userMsg, system)
            return if (summaryMessage != null) {
                listOf(system, summaryMessage) + strategyMessages.drop(1) // drop duplicate system
            } else {
                strategyMessages
            }
        }

        // Legacy: no contextManager, use compressor or full history
        val summary = memoryStore.loadSummary(chatId)
        val summaryMessage = summary?.let {
            ChatMessage(role = "system", content = "[Previous conversation summary]\n$it")
        }

        return if (summaryMessage != null) {
            listOf(system, summaryMessage) + history.toList()
        } else {
            listOf(system) + history.toList()
        }
    }

    override suspend fun getHistory(): List<ChatMessage> {
        ensureLoaded()
        return history.toList()
    }

    override suspend fun reset() {
        history.clear()
        memoryStore.clearHistory(chatId)
        memoryStore.clearSummary(chatId)
        memoryStore.saveFacts(chatId, emptyMap())
        tokenCounter.reset(chatId)
        contextManager?.reset()
        loaded = true
    }

    fun getTokenStats(): TokenCounter.SessionTokens? =
        tokenCounter.getSessionStats(chatId)

    fun getEstimatedHistoryTokens(): Int =
        tokenCounter.estimateHistoryTokens(history)

    suspend fun getSummary(): String? =
        memoryStore.loadSummary(chatId)

    suspend fun compressNow(): String? {
        if (historyCompressor == null) return null
        val existingSummary = memoryStore.loadSummary(chatId)
        val result = historyCompressor.compress(history, existingSummary)
        if (result.wasCompressed && result.summary != null) {
            memoryStore.saveSummary(chatId, result.summary)
        }
        return result.summary
    }

    fun getContextManager(): ContextManager? = contextManager

    fun getCurrentStrategyName(): String =
        contextManager?.getStrategy()?.getName() ?: "full"

    suspend fun switchStrategy(newManager: ContextManager): String {
        val msg = newManager.getStrategy().getName()
        // Load strategy-specific state
        (newManager.getStrategy() as? StickyFactsStrategy)?.let {
            it.setFacts(memoryStore.loadFacts(chatId))
        }
        (newManager.getStrategy() as? BranchingStrategy)?.let {
            it.loadBranches()
        }
        return "Switched to $msg"
    }
}
