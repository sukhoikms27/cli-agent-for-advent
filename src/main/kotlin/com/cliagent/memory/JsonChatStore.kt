package com.cliagent.memory

import com.cliagent.config.AppPaths
import com.cliagent.llm.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

class JsonChatStore(
    private val chatsDir: Path = AppPaths.chatsDir,
    private val longTermStore: JsonLongTermStore = JsonLongTermStore()
) : MemoryStore, AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    /**
     * Единый writer-актор: все операции чтение-модификация-запись над чатами
     * сериализуются в одну очередь. Без этого параллельные шаги (swarm) при
     * read-modify-write теряют сообщения: оба читают один снимок, каждый
     * дописывает своё и перезаписывает файл целиком — запись первого теряется.
     * Чтения идут напрямую: atomicWrite (temp + rename) гарантирует целостный
     * снимок, поэтому читателям сериализация не нужна.
     */
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeQueue = Channel<WriteOp<*>>(Channel.UNLIMITED)

    private class WriteOp<T>(
        val action: suspend () -> T,
        val result: CompletableDeferred<T>
    )

    init {
        writerScope.launch {
            for (op in writeQueue) {
                @Suppress("UNCHECKED_CAST")
                val typed = op as WriteOp<Any?>
                try {
                    typed.result.complete(typed.action())
                } catch (e: CancellationException) {
                    typed.result.completeExceptionally(e)
                    throw e
                } catch (e: Throwable) {
                    typed.result.completeExceptionally(e)
                }
            }
        }
    }

    /** Останавливает writer-актор. Pending-операции добиваются (channel дренируется при close). */
    override fun close() {
        writeQueue.close()
        writerScope.cancel()
    }

    /** Ставит RMW-операцию в очередь актора и ждёт её выполнения. */
    private suspend fun <T> submit(action: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        writeQueue.send(WriteOp(action, result))
        return result.await()
    }

    private suspend fun ensureDir() = withContext(Dispatchers.IO) {
        Files.createDirectories(chatsDir)
    }

    override suspend fun saveMessage(chatId: String, message: ChatMessage) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            messages = chatData.messages + message,
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
    }

    override suspend fun loadHistory(chatId: String): List<ChatMessage> {
        return loadChat(chatId)?.messages ?: emptyList()
    }

    override suspend fun clearHistory(chatId: String) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            messages = emptyList(),
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
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

    override suspend fun createChat(): ChatData = submit {
        ensureDir()
        val now = Instant.now().toString()
        val chatData = ChatData(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            messages = emptyList(),
            createdAt = now,
            updatedAt = now
        )
        atomicWrite(chatFile(chatData.id), json.encodeToString(ChatData.serializer(), chatData))
        chatData
    }

    override suspend fun deleteChat(chatId: String) = submit<Unit> {
        Files.deleteIfExists(chatFile(chatId))
    }

    override suspend fun loadChat(chatId: String): ChatData? {
        return withContext(Dispatchers.IO) { loadChatInternal(chatId) }
    }

    override suspend fun saveSummary(chatId: String, summary: String) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            summary = summary,
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
    }

    override suspend fun loadSummary(chatId: String): String? {
        return loadChat(chatId)?.summary
    }

    override suspend fun clearSummary(chatId: String) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            summary = null,
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
    }

    override suspend fun saveFacts(chatId: String, facts: Map<String, String>) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            facts = facts,
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
    }

    override suspend fun loadFacts(chatId: String): Map<String, String> {
        return loadChat(chatId)?.facts ?: emptyMap()
    }

    override suspend fun createBranch(chatId: String, name: String, leafMessageId: String?, fromIndex: Int): BranchData = submit {
        ensureDir()
        val branch = BranchData(
            id = "branch-${UUID.randomUUID()}",
            name = name,
            leafMessageId = leafMessageId,
            fromIndex = fromIndex,
            createdAt = Instant.now().toString()
        )
        val chatData = loadChatInternal(chatId) ?: return@submit branch
        val updated = chatData.copy(
            branches = chatData.branches + branch,
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
        branch
    }

    override suspend fun listBranches(chatId: String): List<BranchData> {
        return loadChat(chatId)?.branches ?: emptyList()
    }

    override suspend fun deleteBranch(chatId: String, branchId: String) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            branches = chatData.branches.filter { it.id != branchId },
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
    }

    // Working memory — per-chat (день 11)
    override suspend fun saveWorkingMemory(chatId: String, memory: WorkingMemory) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            workingMemory = memory,
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
    }

    override suspend fun loadWorkingMemory(chatId: String): WorkingMemory? {
        return loadChat(chatId)?.workingMemory
    }

    override suspend fun clearWorkingMemory(chatId: String) = submit {
        ensureDir()
        val chatData = loadChatInternal(chatId) ?: return@submit
        val updated = chatData.copy(
            workingMemory = null,
            updatedAt = Instant.now().toString()
        )
        atomicWrite(chatFile(chatId), json.encodeToString(ChatData.serializer(), updated))
    }

    // Long-term memory — global (день 11), форвард в JsonLongTermStore
    override suspend fun loadLongTermMemory(): LongTermMemory = longTermStore.load()

    override suspend fun saveLongTermMemory(memory: LongTermMemory) = longTermStore.save(memory)

    override suspend fun clearLongTermMemory() = longTermStore.clear()

    private fun loadChatInternal(chatId: String): ChatData? {
        val file = chatFile(chatId)
        if (!Files.exists(file)) return null
        return runCatching {
            json.decodeFromString<ChatData>(Files.readString(file))
        }.getOrNull()
    }

    private fun chatFile(chatId: String): Path = chatsDir.resolve("$chatId.json")

    private fun atomicWrite(target: Path, content: String) {
        // Уникальный tmp-файл на вызов — защита от остаточной гонки, если когда-либо
        // появится несериализованный путь записи (напрямую atomicWrite не зовётся снаружи,
        // но дешёвый страховочный слой).
        val tmp = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}
