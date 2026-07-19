package com.cliagent.support

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.config.AppConfig
import com.cliagent.config.ConfigRepository
import com.cliagent.context.ContextManager
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmClientFactory
import com.cliagent.memory.JsonChatStore
import com.cliagent.memory.MemoryStore
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.embedding.OllamaEmbeddingClient
import com.cliagent.support.tools.TicketToolExecutor
import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketStore
import com.cliagent.support.tickets.TicketStore.Companion.defaultTicketsFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * День 33 — сборка support-agent'а поверх cli-agent.
 *
 * Архитектурно похоже на web-app/AgentFactory.kt, но с двумя ключевыми отличиями от motivator'а:
 *  1. **RAG retriever** над FAQ/документацией — ответы опираются на базу знаний продукта.
 *  2. **TicketStore** — контекст тикета пользователя инжектируется в system prompt (статус,
 *     история, описание) — агент отвечает с учётом конкретной ситуации клиента.
 *
 * **Провайдер LLM** (день 33 fix): по умолчанию **cloud z.ai GLM-5.1** (быстрый, качественный —
 * критично для real-time support-чата), с fallback на Ollama для офлайн/VPS-демо. Это выравнивает
 * support-app с днями 31-32 (`ask`, `review-pr`), вместо слепого копирования motivator'а (где
 * local LLM была осознанным выбором для VPS-демо). Embedder всегда Ollama — z.ai не предоставляет
 * embeddings endpoint (см. день 32, GitHub issue).
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
    private val ragIndexFile: Path,
    private val ragEnabled: Boolean = true,
    /** День 33: TicketFieldExtractor для извлечения ticketId/email из free-form текста. */
    val extractor: TicketFieldExtractor = TicketFieldExtractor(client, model),
    /** День 33: включить автоматическое создание тикетов через tool. */
    private val ticketToolEnabled: Boolean = true,
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

        // RAG retriever: shared embedder + support-specific store (читает индекс каждый раз).
        // ВАЖНО: НЕ используем дефолтный AppPaths.ragIndexFile — он расшарен с dev-assistant
        // (индекс над кодом проекта). У support-app — свой изолированный индекс над FAQ.
        val retriever = if (ragEnabled) {
            RagRetriever(embedder = ragEmbedder, store = JsonRagStore(file = ragIndexFile), topK = 5)
        } else {
            null
        }

        // Контекст тикета: ищем по ticketId ИЛИ по customerEmail (последний тикет пользователя).
        val ticket = resolveTicketContext(ticketId, customerEmail)

        // День 33: TicketToolExecutor — даёт агенту тулзу create_ticket для автозаведения тикетов,
        // когда ответа нет в FAQ. In-process (не MCP), без confirm-callback (full autonomy).
        // ВАЖНО: при toolExecutor != null chatStreamed деградирует в batch — это сознательный
        // компромисс (см. plan): для ~90% запросов без тулзы progressive streaming сохраняется.
        val toolExecutor = if (ticketToolEnabled) TicketToolExecutor(ticketStore) else null

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
            toolExecutor = toolExecutor,
            maxToolRounds = 4,   // короткий loop — тулза одна, запас 4 раундов.
            // День 33: переопределить стандартный retrieved-формат (1) Ответ 2) Источники 3) Цитаты)
            // на естественный ответ без citations-секций — пользователь видит только содержательный текст.
            retrievedInstructionOverride = SUPPORT_RETRIEVED_INSTRUCTION,
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
         * День 33: override инструкции retrieved-блока для support-агента.
         *
         * Заменяет стандартный формат «1) Ответ 2) Источники 3) Цитаты» (нужный dev-assistant'у)
         * на естественный ответ без формальных citations-секций. Чанки по-прежнему рендерятся
         * (для обоснования агентом), но в ответе пользователь видит только содержательный текст.
         */
        private val SUPPORT_RETRIEVED_INSTRUCTION = """
            Ответь пользователю естественно и кратко, как живой support-агент в чате.
            НЕ включай в ответ формальные секции «Источники:», «Цитаты:», «(Source: ...)» —
            пользователь видит только содержательный текст.
            Ссылаться на источник можно органично в тексте («Согласно FAQ…», «В документации указано…»),
            если это уместно — но не отдельной секцией.
            Если чанки не содержат ответа на вопрос — используй инструмент create_ticket
            для эскалации и сообщи номер созданного тикета.""".trimIndent()

        /**
         * Фабричный метод из env + cli-agent config.
         *
         * **LLM-провайдер (default: z.ai cloud).** Загружает `~/.config/cli-agent/config.json` через
         * [ConfigRepository] (env override `CLI_AGENT_*` применяется автоматически — API key, model,
         * baseUrl, provider). Это переиспользует тот же конфиг, что и `cli-agent chat` / `ask` /
         * `review-pr` — единый источник правды. Явный override через `SUPPORT_PROVIDER`:
         *  - `zai` (default если не указано) — cloud GLM-5.1 (быстро, качественно; требует CLI_AGENT_API_KEY)
         *  - `ollama` — local Ollama (офлайн/VPS-демо; требует OLLAMA_BASE_URL)
         *
         * **Embedder — всегда Ollama** (`nomic-embed-text`), z.ai не предоставляет embeddings endpoint.
         *
         * env:
         *  - SUPPORT_PROVIDER (default: из config.json или `zai`) — LLM-провайдер: `zai` | `ollama`
         *  - SUPPORT_MODEL (default: из config.json; для zai → glm-5.1, для ollama → qwen3:14b)
         *  - SUPPORT_RAG_EMBEDDING_URL (default: http://127.0.0.1:11434) — base URL Ollama для embeddings
         *  - SUPPORT_RAG_EMBEDDING_MODEL (default: nomic-embed-text) — модель эмбеддингов FAQ
         *  - SUPPORT_RAG_DISABLED (default: false) — отключить RAG (только LLM + tickets)
         */
        fun fromEnv(): SupportAgentFactory {
            // 1. Базовый конфиг из cli-agent (config.json + env overrides CLI_AGENT_*).
            //    ConfigRepository.load() бросает IllegalStateException если apiKey не задан —
            //    для cloud это фатально, для ollama apiKey не нужен, поэтому ловим и собираем
            //    минимальный AppConfig (провайдер мы резолвим ниже из SUPPORT_PROVIDER).
            val baseConfig = try {
                ConfigRepository().load()
            } catch (e: IllegalStateException) {
                AppConfig()
            }

            // 2. Резолв провайдера: SUPPORT_PROVIDER > config.provider > default zai.
            //    Нормализуем "z.ai" (алиас из старых config.json) → канонический "zai" id.
            val rawProvider = System.getenv("SUPPORT_PROVIDER") ?: baseConfig.provider.ifBlank { "zai" }
            val provider = when (rawProvider.lowercase().trim()) {
                "z.ai", "zai" -> "zai"
                "ollama", "ollama-local", "local" -> "ollama"
                else -> rawProvider
            }
            val isOllama = provider == "ollama"

            // 3. Резолв модели: SUPPORT_MODEL > config.model > provider-default.
            val model = System.getenv("SUPPORT_MODEL")?.takeIf { it.isNotBlank() }
                ?: baseConfig.model.ifBlank { if (isOllama) "qwen3:14b" else "glm-5.1" }

            // 4. Сборка AppConfig для LlmClientFactory. provider zai → z.ai base url, ollama → /v1.
            val llmConfig = if (isOllama) {
                val ollamaUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
                baseConfig.copy(
                    provider = "ollama",
                    baseUrl = "$ollamaUrl/v1",
                    model = model,
                    apiKey = "",
                )
            } else {
                baseConfig.copy(
                    provider = provider,
                    model = model,
                    // Для cloud z.ai — baseUrl дефолтный, если в config.json пустой/нечестный.
                    baseUrl = baseConfig.baseUrl.ifBlank { "https://api.z.ai/api/coding/paas/v4" },
                )
            }
            val client = LlmClientFactory.create(llmConfig)

            // 5. Embedder — всегда Ollama (z.ai не отдаёт embeddings).
            val embeddingUrl = System.getenv("SUPPORT_RAG_EMBEDDING_URL")
                ?: System.getenv("OLLAMA_BASE_URL")
                ?: "http://127.0.0.1:11434"
            val embeddingModel = System.getenv("SUPPORT_RAG_EMBEDDING_MODEL") ?: "nomic-embed-text"
            val ragEnabled = System.getenv("SUPPORT_RAG_DISABLED")?.equals("true", ignoreCase = true) != true

            val store = JsonChatStore()
            val ragEmbedder = OllamaEmbeddingClient(baseUrl = embeddingUrl, model = embeddingModel)
            val ticketStore = TicketStore()
            // Изолированный индекс: ~/.local/share/cli-agent/support/rag/index.json
            // (НЕ общий с dev-assistant — там индекс над кодом проекта, не над FAQ).
            val ragIndexFile = defaultTicketsFile().parent.resolve("rag").resolve("index.json")
            // День 33: включить автосоздание тикетов через tool (default: on).
            // env SUPPORT_TICKET_TOOL_DISABLED=true — выключить (например, для read-only демо).
            val ticketToolEnabled = System.getenv("SUPPORT_TICKET_TOOL_DISABLED")
                ?.equals("true", ignoreCase = true) != true
            return SupportAgentFactory(
                client, store, model, ragEmbedder, ticketStore, ragIndexFile, ragEnabled,
                extractor = TicketFieldExtractor(client, model),
                ticketToolEnabled = ticketToolEnabled,
            )
        }
    }
}
