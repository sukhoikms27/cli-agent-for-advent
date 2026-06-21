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
import com.cliagent.llm.token.TokenCounter
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
    private val autoProfileEvery: Int = 0   // 0 = авто-извлечение профиля выключено
) : Agent {

    private var history = mutableListOf<ChatMessage>()
    private var loaded = false
    // Memory layers (день 11): working — per-chat, long-term — global
    private var workingMemory: WorkingMemory? = null
    private var longTermMemory: LongTermMemory? = null
    private var turnCount = 0   // день 12: счётчик ходов для авто-извлечения профиля

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

        val request = ChatRequest(model = model, messages = messagesToSend)
        val result = llmClient.chat(request)

        return when (result) {
            is LlmResult.Success -> {
                val usage = result.data.usage
                tokenCounter.recordUsage(chatId, usage)

                val assistantContent = result.data.choices.first().message.content
                val assistantMsg = ChatMessage(
                    role = "assistant",
                    content = assistantContent,
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

                assistantContent
            }
            is LlmResult.Error -> throw LlmCallException(result.code, result.message)
        }
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
            // Strategy builds messages; summary is added if present
            val strategyMessages = contextManager.buildMessages(history, userMsg, system)
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
