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

    // Working memory — per-chat (данные текущей задачи, день 11)
    suspend fun saveWorkingMemory(chatId: String, memory: WorkingMemory)
    suspend fun loadWorkingMemory(chatId: String): WorkingMemory?
    suspend fun clearWorkingMemory(chatId: String)

    // Long-term memory — global (profile, decisions, knowledge; кросс-чат, день 11)
    suspend fun loadLongTermMemory(): LongTermMemory   // non-null: пустой объект если файла нет
    suspend fun saveLongTermMemory(memory: LongTermMemory)
    suspend fun clearLongTermMemory()
}
