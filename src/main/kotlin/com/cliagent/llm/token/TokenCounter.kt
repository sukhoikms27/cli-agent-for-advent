package com.cliagent.llm.token

import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.Usage

class TokenCounter {

    data class SessionTokens(
        var totalPromptTokens: Long = 0,
        var totalCompletionTokens: Long = 0,
        var totalTokens: Long = 0,
        var requestCount: Int = 0,
        var lastRequestTokens: Usage? = null,
        var totalCachedTokens: Long = 0
    )

    private val sessionStats = mutableMapOf<String, SessionTokens>()

    fun recordUsage(sessionId: String, usage: Usage?) {
        if (usage == null) return
        val stats = sessionStats.getOrPut(sessionId) { SessionTokens() }
        stats.totalPromptTokens += usage.promptTokens
        stats.totalCompletionTokens += usage.completionTokens
        stats.totalTokens += usage.totalTokens
        stats.totalCachedTokens += (usage.promptTokensDetails?.cachedTokens ?: 0)
        stats.requestCount++
        stats.lastRequestTokens = usage
    }

    fun getSessionStats(sessionId: String): SessionTokens? =
        sessionStats[sessionId]

    fun estimateMessageTokens(message: ChatMessage): Int {
        // ~4 chars = 1 token for mixed ru/en text
        return (message.content.length / 4) + 4
    }

    fun estimateHistoryTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessageTokens(it) }

    fun reset(sessionId: String) {
        sessionStats.remove(sessionId)
    }
}
