package com.cliagent.context.strategy

import com.cliagent.llm.model.ChatMessage
import com.cliagent.memory.BranchData
import com.cliagent.memory.MemoryStore

class BranchingStrategy(
    private val memoryStore: MemoryStore,
    private val chatId: String,
    private val windowSize: Int = 10
) : ContextStrategy {

    private var currentBranchId: String = "main"
    private val branches = mutableMapOf<String, BranchData>()

    suspend fun loadBranches() {
        val saved = memoryStore.listBranches(chatId)
        branches.clear()
        saved.forEach { branches[it.id] = it }
    }

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        val branch = branches[currentBranchId]
        val messages = if (branch != null && branch.fromIndex < history.size) {
            history.subList(0, branch.fromIndex + 1) + newMessage
        } else {
            history + newMessage
        }
        val windowed = messages.takeLast(windowSize)
        return listOf(systemPrompt) + windowed
    }

    override fun getName(): String = "branching"

    override fun getDescription(): String =
        "Creates branches from checkpoints, switch between them"

    suspend fun createBranch(name: String, fromIndex: Int, leafMessageId: String? = null): String {
        val branch = memoryStore.createBranch(chatId, name, leafMessageId, fromIndex)
        branches[branch.id] = branch
        return branch.id
    }

    fun switchBranch(branchId: String): Result<String> {
        if (branchId != "main" && branchId !in branches) {
            return Result.failure(IllegalArgumentException("Branch '$branchId' not found"))
        }
        currentBranchId = branchId
        val branchName = branches[branchId]?.name ?: "main"
        return Result.success(branchName)
    }

    fun listBranches(): List<String> {
        return listOf("main${if (currentBranchId == "main") " (current)" else ""}") +
            branches.entries.map { (id, b) ->
                "${b.name}${if (id == currentBranchId) " (current)" else ""}"
            }
    }

    fun getCurrentBranchId(): String = currentBranchId

    override fun reset() {
        branches.clear()
        currentBranchId = "main"
    }
}
