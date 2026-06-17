package com.cliagent.memory

import com.cliagent.llm.model.ChatMessage

interface MemoryStore {
    suspend fun saveMessage(chatId: String, message: ChatMessage)
    suspend fun loadHistory(chatId: String): List<ChatMessage>
    suspend fun clearHistory(chatId: String)
    suspend fun listChats(): List<ChatData>
    suspend fun createChat(): ChatData
    suspend fun deleteChat(chatId: String)
    suspend fun loadChat(chatId: String): ChatData?

    // Summary — compressed conversation context
    suspend fun saveSummary(chatId: String, summary: String)
    suspend fun loadSummary(chatId: String): String?
    suspend fun clearSummary(chatId: String)

    // Facts — key-value memory (StickyFacts strategy)
    suspend fun saveFacts(chatId: String, facts: Map<String, String>)
    suspend fun loadFacts(chatId: String): Map<String, String>

    // Branches — dialog branches (BranchingStrategy)
    suspend fun createBranch(chatId: String, name: String, leafMessageId: String?, fromIndex: Int): BranchData
    suspend fun listBranches(chatId: String): List<BranchData>
    suspend fun deleteBranch(chatId: String, branchId: String)
}
