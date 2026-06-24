package com.cliagent.agent

import com.cliagent.context.ContextManager
import com.cliagent.context.HistoryCompressor
import com.cliagent.context.strategy.BranchingStrategy
import com.cliagent.context.strategy.ContextStrategyType
import com.cliagent.context.strategy.StickyFactsStrategy
import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.PromptTemplates
import com.cliagent.llm.model.ReasoningStrategy
import com.cliagent.llm.model.StagePromptTemplates
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.llm.model.ToolDefinition
import com.cliagent.llm.token.ArtifactLimits
import com.cliagent.llm.token.OutputBudget
import com.cliagent.llm.token.TokenCounter
import com.cliagent.llm.token.truncateToTokens
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.TaskState
import com.cliagent.state.TaskStateMachine
import com.cliagent.state.TaskStage
import com.cliagent.state.TransitionGuard
import com.cliagent.state.TransitionOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    private val contextManager: ContextManager? = null,
    private val profileExtractor: ProfileExtractor? = null,
    private val autoProfileEvery: Int = 0,   // 0 = авто-извлечение профиля выключено
    private val toolExecutor: ToolExecutor? = null   // день 17: null = tools отключены (поведение дней 1–16)
) : Agent {

    /** Доступ к [TokenCounter] для stage-агентов (мера C: bounded-усечение межартефактных передач). */
    fun tokenCounter(): TokenCounter = tokenCounter

    private var history = mutableListOf<ChatMessage>()
    private var loaded = false
    // Memory layers (день 11): working — per-chat, long-term — global
    private var workingMemory: WorkingMemory? = null
    private var longTermMemory: LongTermMemory? = null
    private var turnCount = 0   // день 12: счётчик ходов для авто-извлечения профиля
    // День 17: парсер JSON-аргументов tool_calls от LLM.
    private val toolArgsJson = Json { ignoreUnknownKeys = true }

    private suspend fun ensureLoaded() {
        if (!loaded) {
            history = memoryStore.loadHistory(chatId).toMutableList()
            loaded = true
            // Load memory layers
            workingMemory = memoryStore.loadWorkingMemory(chatId)
            longTermMemory = memoryStore.loadLongTermMemory()
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

        // День 17: tool-use loop. tools = null (нет toolExecutor / MCP недоступен) → один shot,
        // поведение дней 1–16. Иначе LLM может вернуть tool_calls → исполняем → feed-back → финал.
        val tools = loadToolsOrNull()
        return runToolLoop(messagesToSend, OutputBudget.maxTokensFor(estimatedTokens), tools, userMsg)
    }

    /**
     * День 17: tool-use loop. Отправляет запрос (с tools, если есть); если LLM просит tool_calls —
     * исполняет каждый через [toolExecutor], дописывает assistant(c tool_calls) + tool-result
     * сообщения в in-memory scratch (БЕЗ persist в history — иначе ломаем сериализацию/контекст и
     * раздуваем окно) и зовёт LLM снова. Финальный ответ (без tool_calls или при исчерпании
     * [MAX_TOOL_ROUNDS]) persist'ится через [finalizeAssistant].
     */
    private suspend fun runToolLoop(
        initialMessages: List<ChatMessage>,
        maxTokens: Int?,
        tools: List<ToolDefinition>?,
        userMsg: ChatMessage,
    ): String {
        val scratch = initialMessages.toMutableList()
        var rounds = 0
        while (true) {
            val request = ChatRequest(
                model = model,
                messages = scratch.toList(),
                maxTokens = maxTokens,
                tools = tools,
                toolChoice = tools?.let { "auto" },
            )
            val result = llmClient.chat(request)
            when (result) {
                is LlmResult.Error -> throw LlmCallException(result.code, result.message)
                is LlmResult.Success -> {
                    tokenCounter.recordUsage(chatId, result.data.usage)
                    val choice = result.data.choices.first()
                    // Мера B: обрыв по длине — бросаем типизированный сигнал (частичный ответ не persist).
                    if (choice.finishReason == "length") {
                        throw LlmCallException.truncated(choice.message.content)
                    }
                    val calls = choice.message.toolCalls
                    if (calls.isNullOrEmpty() || rounds >= MAX_TOOL_ROUNDS) {
                        return finalizeAssistant(choice.message, userMsg)
                    }
                    // исполняем tool_calls; промежуточные сообщения — только в scratch (не в history)
                    scratch.add(choice.message)
                    for (tc in calls) {
                        val args = parseToolArgs(tc.function.arguments)
                        val toolResult = execTool(tc.function.name, args)
                        scratch.add(ChatMessage(role = "tool", content = toolResult, toolCallId = tc.id))
                    }
                    rounds++
                }
            }
        }
    }

    /** Persist'ит финальный assistant-ответ + post-processing (strategies, profile). День 17: extracted. */
    private suspend fun finalizeAssistant(message: ChatMessage, userMsg: ChatMessage): String {
        val assistantContent = message.content
        // Мера D1: при активной задаче артефакт уже лежит в TaskState и инжектируется следующей
        // стадией; в history — усечённая копия (полный ответ съедал окно → обрыв).
        val taskActive = getTaskState() != null
        val forHistory = if (taskActive) {
            truncateToTokens(assistantContent, ArtifactLimits.HISTORY_STAGE_MSG_TOKENS)
        } else {
            assistantContent
        }
        val assistantMsg = ChatMessage(
            role = "assistant",
            content = forHistory,
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

        // День 12: авто-извлечение профиля каждые N ходов (opt-in)
        turnCount++
        if (profileExtractor != null && autoProfileEvery > 0 && turnCount % autoProfileEvery == 0) {
            val current = getProfile()
            val inferred = profileExtractor.extract(history, current)
            setProfile(profileExtractor.mergeProfile(current, inferred))
        }

        return assistantContent
    }

    /** Schemas tools для запроса; null если toolExecutor нет/недоступен (graceful — без tools). */
    private suspend fun loadToolsOrNull(): List<ToolDefinition>? {
        val executor = toolExecutor ?: return null
        return try {
            executor.definitions().ifEmpty { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            println("⚠️ MCP tools unavailable: ${e.message}; proceeding without tools.")
            null
        }
    }

    /** Исполняет tool; при ошибке возвращает строку-описание (LLM может самокорректироваться). */
    private suspend fun execTool(name: String, args: Map<String, Any?>): String {
        val executor = toolExecutor ?: return "Tool '$name' unavailable: no tool executor."
        return try {
            executor.call(name, args)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            "Tool '$name' failed: ${e.message}"
        }
    }

    /** Парсит JSON-строку аргументов от LLM в map примитивов (для McpClient.callTool). */
    private fun parseToolArgs(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        val element = try {
            toolArgsJson.parseToJsonElement(raw)
        } catch (_: Throwable) {
            return emptyMap()
        }
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.entries.associate { (k, v) -> k to jsonElementToAny(v) }
    }

    private fun jsonElementToAny(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.content == "true" -> true
            el.content == "false" -> false
            el.content.toIntOrNull() != null -> el.content.toInt()
            el.content.toLongOrNull() != null -> el.content.toLong()
            el.content.toDoubleOrNull() != null -> el.content.toDouble()
            else -> el.content
        }
        is JsonObject -> el.entries.associate { (k, v) -> k to jsonElementToAny(v) }
        is JsonArray -> el.map { jsonElementToAny(it) }
    }

    private companion object {
        const val MAX_TOOL_ROUNDS = 4
    }

    private suspend fun buildMessagesToSend(userMsg: ChatMessage): List<ChatMessage> {
        // Precedence (доработка Day 13): активная задача → stage-промпт (поведение per stage);
        // иначе reasoningStrategy → иначе статический systemPrompt (поведение Day 1-13).
        val taskState = getTaskState()
        val baseSystem = when {
            taskState != null -> StagePromptTemplates.buildSystemMessage(taskState.stage, taskState.taskKind)
            reasoningStrategy != null -> PromptTemplates.buildSystemMessage(reasoningStrategy)
            else -> systemPrompt
        }
        // Слоёный system prompt: base + [long-term] + [working]; пустые слои элизируются
        val system = PromptBuilder(baseSystem, longTermMemory, workingMemory).build()

        // If contextManager is set, delegate to strategy
        if (contextManager != null) {
            val summary = memoryStore.loadSummary(chatId)
            val summaryMessage = summary?.let {
                ChatMessage(role = "system", content = "[Previous conversation summary]\n$it")
            }
            // Контракт стратегий: newMessage НЕ входит в history (все 4 стратегии делают
            // `history + newMessage`). Но chat() уже добавил userMsg в history (нужно legacy-пути
            // без contextManager + ранний persist) → убираем его, иначе user-сообщение уезжает в
            // LLM дважды (Day 17: замечено в debug-дампе Request body как дублированный user msg).
            val historyForStrategy = history.filterNot { it.id == userMsg.id }
            // Strategy builds messages; summary is added if present
            val strategyMessages = contextManager.buildMessages(historyForStrategy, userMsg, system)
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
        memoryStore.clearWorkingMemory(chatId)
        workingMemory = null
        // long-term НЕ чистим — он global/кросс-сессионный
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

    // ── Memory layer accessors (день 11, для /memory команды) ──

    suspend fun getWorkingMemory(): WorkingMemory? =
        workingMemory ?: memoryStore.loadWorkingMemory(chatId)

    suspend fun getLongTermMemory(): LongTermMemory =
        longTermMemory ?: memoryStore.loadLongTermMemory()

    suspend fun setWorkingMemory(memory: WorkingMemory) {
        memoryStore.saveWorkingMemory(chatId, memory)
        workingMemory = memory
    }

    suspend fun setLongTermMemory(memory: LongTermMemory) {
        memoryStore.saveLongTermMemory(memory)
        longTermMemory = memory
    }

    // ── Profile accessors (день 12, для /profile команды) ──

    suspend fun getProfile(): UserProfile? = getLongTermMemory().profile

    suspend fun setProfile(profile: UserProfile?) {
        setLongTermMemory(getLongTermMemory().copy(profile = profile))
    }

    // ── Project invariants accessors (день 14, для /invariants команды) ──

    suspend fun getInvariants(): List<Invariant> = getLongTermMemory().invariants

    suspend fun setInvariants(invariants: List<Invariant>) {
        setLongTermMemory(getLongTermMemory().copy(invariants = invariants))
    }

    /** Добавить инвариант (если id уже есть — обновить rule/category, не дублировать). */
    suspend fun addInvariant(invariant: Invariant) {
        val current = getInvariants()
        val updated = (current.filterNot { it.id == invariant.id } + invariant)
            .sortedBy { it.category.name }   // стабильный порядок для отображения
        setInvariants(updated)
    }

    /** Удалить инвариант по id; true если был удалён. */
    suspend fun removeInvariant(id: String): Boolean {
        val current = getInvariants()
        if (current.none { it.id == id }) return false
        setInvariants(current.filterNot { it.id == id })
        return true
    }

    // ── Task state accessors (день 13, для /task команды) ──

    suspend fun getTaskState(): TaskState? = getWorkingMemory()?.taskState

    suspend fun setTaskState(state: TaskState?) {
        val w = getWorkingMemory() ?: WorkingMemory()
        setWorkingMemory(w.copy(taskState = state))
    }

    /**
     * Канонический переход вперёд ([TaskStateMachine.next]); null если некуда/нет задачи.
     *
     * День 15: делегирует в [attemptTransition] (единый путь через guard). `next(stage)` всегда
     * возвращает легальный forward-canonical переход, поэтому outcome = Allowed или ArtifactMissing
     * (Illegal невозможен). При ArtifactMissing — поведение как раньше: состояние не меняется,
     * возвращается null (канонический advance «не состоялся»). Для машиночитаемой причины блокировки
     * используй [attemptTransition] напрямую.
     */
    suspend fun advanceTaskState(note: String? = null): TaskState? {
        val cur = getTaskState() ?: return null
        val nextStage = TaskStateMachine.next(cur.stage) ?: return null
        val outcome = attemptTransition(nextStage)
        return when (outcome) {
            is TransitionOutcome.Allowed -> outcome.newState
            is TransitionOutcome.ArtifactMissing,
            is TransitionOutcome.Illegal,
            null -> null
        }
    }

    /**
     * Контролируемый переход через [TransitionGuard] (день 15).
     *
     * Единая точка перехода для CLI/оркестратора: проверяет легальность + артефакт, возвращает
     * типобезопасный [TransitionOutcome]. При [TransitionOutcome.Allowed] — персистит новое состояние.
     * При Illegal/ArtifactMissing — состояние НЕ меняется, потребитель сам решает реакцию.
     *
     * @param to    целевая стадия
     * @param force осознанный escape (note="forced" в history); обходит все правила
     * @return outcome перехода; null если нет активной задачи (taskState == null)
     */
    suspend fun attemptTransition(to: TaskStage, force: Boolean = false): TransitionOutcome? {
        val cur = getTaskState() ?: return null
        val outcome = TransitionGuard.attempt(cur, to, force)
        if (outcome is TransitionOutcome.Allowed) {
            setTaskState(outcome.newState)
        }
        return outcome
    }

    /** Откат на одну стадию назад по history; null если история пуста/нет задачи. */
    suspend fun revertTaskState(): TaskState? {
        val cur = getTaskState() ?: return null
        val reverted = TaskStateMachine.back(cur) ?: return null
        setTaskState(reverted)
        return reverted
    }
}
