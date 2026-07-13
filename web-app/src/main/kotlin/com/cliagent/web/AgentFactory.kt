package com.cliagent.web

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.config.AppConfig
import com.cliagent.context.ContextManager
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmClientFactory
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.memory.JsonChatStore
import com.cliagent.memory.MemoryStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * День 30: сборка агента «Мотиватор» поверх cli-agent.
 *
 * Переиспользует [ContextAwareAgent] (день 7+) и [LlmClientFactory] (день 25) из корневого проекта
 * через `implementation(project(":"))`. Один [LlmClient] + один [JsonChatStore] — синглтоны на всё
 * приложение (JsonChatStore использует actor-backed writes — безопасно под конкурентными запросами).
 *
 * **Per-session агент:** на каждый запрос создаётся свежий [ContextAwareAgent] с нужной температурой
 * (из слайдера UI). История не теряется — она персистится в [JsonChatStore] по chatId и загружается
 * через `ensureLoaded()` при первом `chatStreamed`.
 *
 * **sessionId → chatId маппинг:** [MemoryStore.createChat] генерирует собственный UUID (не принимает
 * id аргументом), поэтому внешний анонимный sessionId (cookie) маппится на внутренний chatId через
 * [sessionChats]. Это сохраняет изоляцию: каждый sessionId → свой chatId → свой файл в store.
 *
 * Окно контекста — [SlidingWindowStrategy](5): не больше 5 сообщений в промпте (требование задания).
 */
class AgentFactory(
    private val client: LlmClient,
    private val store: MemoryStore,
    private val model: String,
) {

    /** sessionId (cookie) → chatId (внутренний UUID MemoryStore). ConcurrentHashMap — thread-safe. */
    private val sessionChats = ConcurrentHashMap<String, String>()

    /** Мьютекс на создание чата (suspend внутри synchronized запрещён). */
    private val createMutex = Mutex()

    /**
     * Создать агента для sessionId с заданной температурой.
     *
     * Suspend: первый запрос в сессии создаёт чат в [MemoryStore] (через createChat), маппит его id
     * в [sessionChats]. Иначе saveMessage молча no-op'ает (JsonChatStore ранний return для неизвестного chatId).
     */
    suspend fun createFor(sessionId: String, temperature: Double): ContextAwareAgent {
        val chatId = resolveChatId(sessionId)
        val contextManager = ContextManager(SlidingWindowStrategy(windowSize = 5))
        return ContextAwareAgent(
            llmClient = client,
            memoryStore = store,
            model = model,
            chatId = chatId,
            systemPrompt = SystemPrompts.motivator,
            contextManager = contextManager,
            temperature = temperature,
            // Логгер в stdout — для дебага на VPS через journalctl. В проде можно загушить.
            logger = { msg -> if (debug) println("[motivator] $msg") },
        )
    }

    /** Получить (или создать) chatId для sessionId. Idempotent — безопасно для повторных запросов. */
    private suspend fun resolveChatId(sessionId: String): String {
        sessionChats[sessionId]?.let { return it }
        // Double-checked под мьютексом (suspend-safe): повторная проверка после захвата.
        return createMutex.withLock {
            sessionChats[sessionId] ?: run {
                // createChat генерирует UUID и пишет пустой ChatData в store.
                val chatData = store.createChat()
                sessionChats[sessionId] = chatData.id
                chatData.id
            }
        }
    }

    /**
     * История чата для sessionId — напрямую из [MemoryStore], без создания агента.
     * Только role/content — UI показывает пузыри сообщений.
     */
    suspend fun historyFor(sessionId: String): HistoryResponse {
        val chatId = sessionChats[sessionId] ?: return HistoryResponse(emptyList())
        val history = store.loadHistory(chatId)
        val messages = history
            .filter { it.role == "user" || it.role == "assistant" }
            .map { HistoryMessage(it.role, it.content) }
        return HistoryResponse(messages)
    }

    companion object {
        /** Включить детальные логи агента (env MOTIVATOR_DEBUG=true). */
        private val debug = System.getenv("MOTIVATOR_DEBUG")?.equals("true", ignoreCase = true) == true

        /**
         * Фабричный метод: читает env-vars деплоя VPS и собирает компоненты.
         *
         * env:
         *  - OLLAMA_BASE_URL (default http://127.0.0.1:11434) — native base без /v1
         *  - MOTIVATOR_MODEL (default qwen2.5:7b-instruct-q5_K_M)
         *  - XDG_DATA_HOME — переопределяет путь к чатам (default /opt/motivator/data)
         */
        fun fromEnv(): AgentFactory {
            val baseUrl = System.getenv("OLLAMA_BASE_URL") ?: "http://127.0.0.1:11434"
            val model = System.getenv("MOTIVATOR_MODEL") ?: "qwen2.5:7b-instruct-q5_K_M"
            // provider=ollama → OllamaNativeClient, apiKey не требуется (нет auth на localhost).
            val config = AppConfig(
                provider = "ollama",
                baseUrl = baseUrl,
                model = model,
                apiKey = "",
            )
            val client = LlmClientFactory.create(config)
            // JsonChatStore дефолтит на AppPaths.chatsDir (XDG_DATA_HOME). Один синглтон-стор.
            val store = JsonChatStore()
            return AgentFactory(client, store, model)
        }
    }
}
