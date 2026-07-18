package com.cliagent.support

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.config.AppConfig
import com.cliagent.context.ContextManager
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmClientFactory
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.memory.JsonChatStore
import com.cliagent.memory.MemoryStore
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.embedding.OllamaEmbeddingClient
import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * День 33 — сборка support-agent'а поверх cli-agent.
 *
 * Зеркало web-app/AgentFactory.kt + два ключевых отличия:
 *  1. **RAG retriever** над FAQ/документацией — ответы опираются на базу знаний продукта.
 *  2. **TicketStore** — контекст тикета пользователя инжектируется в system prompt (статус,
 *     история, описание) — агент отвечает с учётом конкретной ситуации клиента.
 *
 * Per-session агент (как motivator): каждый запрос — свежий ContextAwareAgent, история персистится
 * в JsonChatStore по chatId. SlidingWindow(8) — чуть длиннее motivator'а (5), т.к. поддержка часто
 * требует контекста диалога для диагностики.
 *
 * **RAG lifecycle:** OllamaEmbeddingClient — синглтон на приложение (shared across sessions). Создаётся
 * в [fromEnv], НЕ закрывается per-request (лёгкий HttpClient, нет активного соединения до первого embed).
 * JsonRagStore читает индекс при каждом retrieve (atomic read, no caching — для простоты MVP).
 */
class SupportAgentFactory(
    private val client: LlmClient,
    private val store: MemoryStore,
    private val model: String,
    private val ragEmbedder: OllamaEmbeddingClient,
    private val ticketStore: TicketStore,
    private val ragEnabled: Boolean = true,
) {

    private val sessionChats = ConcurrentHashMap<String, String>()
    private val createMutex = Mutex()

    /**
     * Создать агента для sessionId, опционально с контекстом тикета [ticketId].
     *
     * Если [ticketId] задан и тикет найден — его данные вшиваются в system prompt (через
     * [buildSupportSystemPrompt]). Иначе — базовый [SystemPrompts.supportAgent].
     */
    suspend fun createFor(
        sessionId: String,
        ticketId: Int? = null,
        customerEmail: String? = null,
    ): ContextAwareAgent {
        val chatId = resolveChatId(sessionId)
        val contextManager = ContextManager(SlidingWindowStrategy(windowSize = 8))

        // RAG retriever: shared embedder + per-call store (читает индекс каждый раз).
        val retriever = if (ragEnabled) {
            RagRetriever(embedder = ragEmbedder, store = JsonRagStore(), topK = 5)
        } else {
            null
        }

        // Контекст тикета: ищем по ticketId ИЛИ по customerEmail (последний тикет пользователя).
        val ticket = resolveTicketContext(ticketId, customerEmail)

        return ContextAwareAgent(
            llmClient = client,
            memoryStore = store,
            model = model,
            chatId = chatId,
            systemPrompt = buildSupportSystemPrompt(ticket),
            contextManager = contextManager,
            temperature = 0.4,   // ниже motivator (0.7) — поддержка требует точности, не креатива
            logger = { msg -> if (debug) println("[support] $msg") },
            ragRetriever = retriever,
            ragEnabled = ragEnabled,
            dontKnowThreshold = 0.3f,   // анти-галлюцинация: при слабом контексте → «не знаю»
        )
    }

    /** Получить (или создать) chatId для sessionId. Idempotent. */
    private suspend fun resolveChatId(sessionId: String): String {
        sessionChats[sessionId]?.let { return it }
        return createMutex.withLock {
            sessionChats[sessionId] ?: run {
                val chatData = store.createChat()
                sessionChats[sessionId] = chatData.id
                chatData.id
            }
        }
    }

    /**
     * Резолвить контекст тикета: приоритет — явный [ticketId], иначе последний тикет по [customerEmail].
     * null если ничего не найдено (ответ без ticket-context).
     */
    private suspend fun resolveTicketContext(ticketId: Int?, customerEmail: String?): Ticket? {
        ticketId?.let { return ticketStore.find(it) }
        customerEmail?.let { return ticketStore.findByEmail(it).maxByOrNull { it.id } }
        return null
    }

    /**
     * Сборка system-prompt: базовый [SystemPrompts.supportAgent] + блок [Ticket context] если тикет есть.
     * Делегирует в чистую [buildSupportSystemPrompt] (тестируется без IO). Ticket-контекст даёт агенту
     * «память» о ситуации клиента без необходимости повторять проблему.
     */
    private fun buildSupportSystemPrompt(ticket: Ticket?): com.cliagent.llm.model.ChatMessage =
        com.cliagent.support.buildSupportSystemPrompt(ticket)

    /** История чата для sessionId. */
    suspend fun historyFor(sessionId: String): HistoryResponse {
        val chatId = sessionChats[sessionId] ?: return HistoryResponse(emptyList())
        val history = store.loadHistory(chatId)
        val messages = history
            .filter { it.role == "user" || it.role == "assistant" }
            .map { HistoryMessage(it.role, it.content) }
        return HistoryResponse(messages)
    }

    companion object {
        private val debug = System.getenv("SUPPORT_DEBUG")?.equals("true", ignoreCase = true) == true

        /**
         * Фабричный метод из env (зеркало AgentFactory.fromEnv в web-app).
         *
         * env:
         *  - OLLAMA_BASE_URL (default http://127.0.0.1:11434) — native base без /v1
         *  - SUPPORT_MODEL (default qwen2.5:7b-instruct-q5_K_M) — LLM для ответов
         *  - SUPPORT_RAG_EMBEDDING_MODEL (default nomic-embed-text) — модель эмбеддингов для FAQ
         *  - SUPPORT_RAG_DISABLED (default false) — отключить RAG (только LLM + tickets)
         */
        fun fromEnv(): SupportAgentFactory {
            val baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
            val model = System.getenv("SUPPORT_MODEL") ?: "qwen2.5:7b-instruct-q5_K_M"
            val embeddingModel = System.getenv("SUPPORT_RAG_EMBEDDING_MODEL") ?: "nomic-embed-text"
            val ragEnabled = System.getenv("SUPPORT_RAG_DISABLED")?.equals("true", ignoreCase = true) != true

            val config = AppConfig(
                provider = "ollama",
                baseUrl = baseUrl,
                model = model,
                apiKey = "",
            )
            val client = LlmClientFactory.create(config)
            val store = JsonChatStore()
            val ragEmbedder = OllamaEmbeddingClient(baseUrl = baseUrl, model = embeddingModel)
            val ticketStore = TicketStore()
            return SupportAgentFactory(client, store, model, ragEmbedder, ticketStore, ragEnabled)
        }
    }
}
