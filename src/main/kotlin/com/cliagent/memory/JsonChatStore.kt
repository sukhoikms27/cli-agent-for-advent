package com.cliagent.memory

import com.cliagent.config.AppPaths
import com.cliagent.llm.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

class JsonChatStore(
    private val chatsDir: Path = AppPaths.chatsDir
) : MemoryStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private suspend fun ensureDir() = withContext(Dispatchers.IO) {
        Files.createDirectories(chatsDir)
    }

    override suspend fun saveMessage(chatId: String, message: ChatMessage) {
        ensureDir()
        withContext(Dispatchers.IO) {
            val chatData = loadChatInternal(chatId) ?: return@withContext
            val updated = chatData.copy(
                messages = chatData.messages + message,
                updatedAt = Instant.now().toString()
            )
            atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        }
    }

    override suspend fun loadHistory(chatId: String): List<ChatMessage> {
        return loadChat(chatId)?.messages ?: emptyList()
    }

    override suspend fun clearHistory(chatId: String) {
        ensureDir()
        withContext(Dispatchers.IO) {
            val chatData = loadChatInternal(chatId) ?: return@withContext
            val updated = chatData.copy(
                messages = emptyList(),
                updatedAt = Instant.now().toString()
            )
            atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        }
    }

    override suspend fun listChats(): List<ChatData> {
        ensureDir()
        return withContext(Dispatchers.IO) {
            Files.list(chatsDir).use { stream ->
                stream
                    .filter { it.toString().endsWith(".json") }
                    .map { path ->
                        runCatching {
                            json.decodeFromString<ChatData>(Files.readString(path))
                        }.getOrNull()
                    }
                    .toList()
                    .filterNotNull()
                    .sortedByDescending { it.updatedAt }
            }
        }
    }

    override suspend fun createChat(): ChatData {
        ensureDir()
        val now = Instant.now().toString()
        val chatData = ChatData(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            messages = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        withContext(Dispatchers.IO) {
            atomicWrite(chatFile(chatData.id), json.encodeToString(ChatData.serializer(), chatData))
        }
        return chatData
    }

    override suspend fun deleteChat(chatId: String) {
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(chatFile(chatId))
        }
    }

    override suspend fun loadChat(chatId: String): ChatData? {
        return withContext(Dispatchers.IO) { loadChatInternal(chatId) }
    }

    override suspend fun saveSummary(chatId: String, summary: String) {
        ensureDir()
        withContext(Dispatchers.IO) {
            val chatData = loadChatInternal(chatId) ?: return@withContext
            val updated = chatData.copy(
                summary = summary,
                updatedAt = Instant.now().toString()
            )
            atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        }
    }

    override suspend fun loadSummary(chatId: String): String? {
        return loadChat(chatId)?.summary
    }

    override suspend fun clearSummary(chatId: String) {
        ensureDir()
        withContext(Dispatchers.IO) {
            val chatData = loadChatInternal(chatId) ?: return@withContext
            val updated = chatData.copy(
                summary = null,
                updatedAt = Instant.now().toString()
            )
            atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        }
    }

    override suspend fun saveFacts(chatId: String, facts: Map<String, String>) {
        ensureDir()
        withContext(Dispatchers.IO) {
            val chatData = loadChatInternal(chatId) ?: return@withContext
            val updated = chatData.copy(
                facts = facts,
                updatedAt = Instant.now().toString()
            )
            atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        }
    }

    override suspend fun loadFacts(chatId: String): Map<String, String> {
        return loadChat(chatId)?.facts ?: emptyMap()
    }

    override suspend fun createBranch(chatId: String, name: String, leafMessageId: String?, fromIndex: Int): BranchData {
        ensureDir()
        val branch = BranchData(
            id = "branch-${UUID.randomUUID()}",
            name = name,
            leafMessageId = leafMessageId,
            fromIndex = fromIndex,
            createdAt = Instant.now().toString()
        )
        withContext(Dispatchers.IO) {
            val chatData = loadChatInternal(chatId) ?: return@withContext
            val updated = chatData.copy(
                branches = chatData.branches + branch,
                updatedAt = Instant.now().toString()
            )
            atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        }
        return branch
    }

    override suspend fun listBranches(chatId: String): List<BranchData> {
        return loadChat(chatId)?.branches ?: emptyList()
    }

    override suspend fun deleteBranch(chatId: String, branchId: String) {
        ensureDir()
        withContext(Dispatchers.IO) {
            val chatData = loadChatInternal(chatId) ?: return@withContext
            val updated = chatData.copy(
                branches = chatData.branches.filter { it.id != branchId },
                updatedAt = Instant.now().toString()
            )
            atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        }
    }

    private fun loadChatInternal(chatId: String): ChatData? {
        val file = chatFile(chatId)
        if (!Files.exists(file)) return null
        return runCatching {
            json.decodeFromString<ChatData>(Files.readString(file))
        }.getOrNull()
    }

    private fun chatFile(chatId: String): Path = chatsDir.resolve("$chatId.json")

    private fun atomicWrite(target: Path, content: String) {
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        Files.writeString(tmp, content)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
