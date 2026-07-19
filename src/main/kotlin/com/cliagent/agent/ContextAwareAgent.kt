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
import com.cliagent.llm.model.StreamChunk
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
import com.cliagent.rag.CannedResponses
import com.cliagent.rag.CitationDetector
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.ScoredChunk
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
    /**
     * День 21 (волна W6.3): температура LLM основного цикла. Передаётся в [ChatRequest.temperature].
     * Default `null` (провайдерский дефолт ~0.7-1.0) — сохраняет прежнее поведение для дней 1-20.
     * CLI `--temperature` прокидывается сюда; классификаторы/экстракторы остаются на `0.0` (детерминизм).
     */
    private val temperature: Double? = null,
    /**
     * День 31: cross-provider sampling-параметры основного цикла. Все nullable, default `null`
     * (провайдерский дефолт в wire) — backward-compat с днями 1–30 (агент не отправлял их). CLI-флаги
     * (`--top-p`, `--top-k`, `--seed`, `--stop`, `--frequency-penalty`, `--presence-penalty`) прокидываются
     * сюда из [ChatCommand.buildSession] с priority CLI > config.sampling > null. Пробрасываются в обе
     * точки сборки [ChatRequest] ([chatStreamed], [runToolLoop]). Классификаторы/экстракторы остаются
     * без них (детерминизм на их собственных вызовах через StageAgent/IntentClassifier).
     */
    private val topP: Double? = null,
    private val topK: Int? = null,
    private val seed: Long? = null,
    private val stop: List<String>? = null,
    private val frequencyPenalty: Double? = null,
    private val presencePenalty: Double? = null,
    private val contextLimit: Int = 128000,
    private val historyCompressor: HistoryCompressor? = null,
    private val contextManager: ContextManager? = null,
    private val profileExtractor: ProfileExtractor? = null,
    private val autoProfileEvery: Int = 0,   // 0 = авто-извлечение профиля выключено
    /**
     * День 17: null = tools отключены (поведение дней 1–16).
     *
     * День 34 (stage/swarm интеграция): `var` для динамического переключения по taskKind.
     * TaskOrchestrator при старте задачи определяет FILE_OP/PR_REVIEW и меняет executor:
     *  - FILE_OP → FileToolExecutor с DangerousOpGate (без MCP filesystem — он обходит gate).
     *  - иначе → CompositeToolExecutor с MCP (включая filesystem) + опционально read-only file-tools.
     */
    private var toolExecutor: ToolExecutor? = null,
    /**
     * День 20: лимит раундов tool-use loop. Default **8** (вместо прежнего `const 4`) — для
     * «длинного флоу» оркестрации нескольких MCP-серверов (search→read→format→save + повторы).
     * Конфигурируется через [com.cliagent.config.AppConfig.maxToolRounds] (config.json / env).
     */
    private val maxToolRounds: Int = 8,
    /**
     * День 33 (support-app refinement): override инструкции retrieved-context блока.
     *
     * Default null → стандартный формат PromptBuilder («1) Ответ / 2) Источники / 3) Цитаты»),
     * нужный dev-assistant'у для прозрачности источников. Support-агент передаёт непустую
     * строку, чтобы убрать обязательные citations-секции (конечный пользователь их не видит).
     * См. [com.cliagent.agent.PromptBuilder].
     */
    private val retrievedInstructionOverride: String? = null,
    /**
     * День 19: sink для статусного вывода (compress-warnings, tool-call-лог). **Default `::println`**
     * сохраняет поведение вне REPL (тесты, batch). В REPL подключается к [com.cliagent.cli.AppTerminal.println] —
     * критично: спиннер крутится **во время** chat(), и сырой `println` (stdout) затирается анимацией
     * mordant. `AppTerminal.println` печатает «поверх» активного спиннера (как stage-блоки через onEmit).
     * Агент не зависит от CLI-слоя — только от типа `(String) -> Unit`.
     */
    private val logger: (String) -> Unit = { msg -> println(msg) },
    /**
     * День 22: RAG-retriever для инъекции retrieved-чанков в промпт. **null = RAG отключён**
     * (поведение дней 1–21). Как и [toolExecutor], опциональная nullable-зависимость — агент не
     * зависит от CLI/Ollama, пока retrieval не запрошен через [ragEnabled].
     */
    private val ragRetriever: RagRetriever? = null,
    /**
     * День 22: runtime-флаг RAG-режима (toggle через `/rag on|off`). Дефолт — из
     * [com.cliagent.rag.RagConfig.enabled]. Когда true И [ragRetriever] != null, каждый ход
     * берёт top-K чанков по запросу и кладёт в `[Retrieved context]`-блок промпта.
     */
    private var ragEnabled: Boolean = false,
    /**
     * День 24: порог анти-галлюцинации. Если max similarity среди retrieved-чанков < порога (или
     * список пуст) → canned-response «не знаю» **без вызова LLM**. `0.0` = режим выключен
     * (backward-compat с днём 23). Отдельно от [com.cliagent.rag.RagConfig.similarityThreshold]
     * (тот фильтрует чанки для реранкера; этот — отказывается отвечать при слабом контексте).
     * Не срабатывает при `retrieve()=null` (мягкая деградация дня 22).
     */
    private val dontKnowThreshold: Float = 0.0f,
    /**
     * День 25: conversation-aware retrieval. `true` → перед `retrieve()` запрос обогащается целью
     * диалога (`WorkingMemory.currentTask`) + последними 2 user-репликами истории (см.
     * [buildConversationQuery]). Follow-up «а сколько для этого?» находят контекст (production-like).
     * `false` (default) → для эмбеддинга берётся только `userMessage` (байт-идентично дню 24).
     *
     * TDD-починка: `var` + [setConversationalQuery] (симметрия с `setRagEnabled` дня 22) — чтобы
     * `/rag scenario` мог форсировать режим в рантайме и восстанавливать в finally.
     */
    private var conversationalQuery: Boolean = false,
    /**
     * День 34 (refinement): минимальная длина сообщения для RAG-retrieval. Короткие сообщения
     * («да», «переходим») не триггерят retrieval — это устраняет загрязнение контекста и лишние
     * embedding-вызовы. Default 0 (backward-compat: всегда retrieve, как дней 22-33).
     * В продакшн-wiring (ChatCommand.buildSession) передаётся 20 — отсеивать подтверждения.
     */
    private val ragMinQueryChars: Int = 0,
    /**
     * День 24: sink для результата пост-чека цитирования ([CitationDetector.detect]). **Default
     * noop** — сам чек идёт через [logger] (warning поверх спиннера). Этот колбэк — для `/rag eval`
     * и тестов, чтобы собрать метрику % ответов с источниками/цитатами без повторного детектирования.
     */
    private val citationLogger: (CitationDetector.Result) -> Unit = {},
) : Agent {

    /** Доступ к [TokenCounter] для stage-агентов (мера C: bounded-усечение межартефактных передач). */
    fun tokenCounter(): TokenCounter = tokenCounter

    private var history = mutableListOf<ChatMessage>()
    private var loaded = false
    // Memory layers (день 11): working — per-chat, long-term — global
    private var workingMemory: WorkingMemory? = null
    private var longTermMemory: LongTermMemory? = null
    private var turnCount = 0   // день 12: счётчик ходов для авто-извлечения профиля
    // День 24: буфер retrieved-контекста текущего хода для пост-чека цитирования в finalizeAssistant.
    private var ragContextAtLastTurn: List<ScoredChunk>? = null
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
                logger("🔄 Compressing history...")
                val existingSummary = memoryStore.loadSummary(chatId)
                val result = historyCompressor.compress(history, existingSummary)
                if (result.wasCompressed && result.summary != null) {
                    memoryStore.saveSummary(chatId, result.summary)
                    logger("✓ Compressed ${result.summarizedCount} messages (~${result.tokenEstimate} tokens in summary)")
                }
            }
        }

        // День 22: RAG-retrieval. Каждый ход — свежий поиск top-K чанков по запросу (лекция недели 5:
        // инференс-тайм подгрузка). ragEnabled=false или нет retriever'а → null → без [Retrieved context].
        // Мягкая деградация: ошибка эмбеддинга/пустой индекс → ragContext=null → агент отвечает без RAG.
        // День 25: conversation-aware retrieval — запрос обогащается целью диалога + последними
        // репликами, чтобы follow-up находили контекст. conversationalQuery=false → только userMessage.
        //
        // День 34 (refinement): heuristic pre-filter — пропускаем retrieval для коротких команд
        // и подтверждений перехода стадии («да», «переходим», «нет», «y»). Они не являются
        // семантическими запросами к базе знаний, retrieval только загрязняет контекст + тратит
        // embedding-вызовы. Фильтр: <20 символов И не содержит вопросительных слов/файловых путей.
        val ragContext = if (isRagEnabled() && shouldRetrieve(userMessage)) {
            val retrievalQuery = if (conversationalQuery) buildConversationQuery(userMessage) else userMessage
            val hits = ragRetriever?.retrieve(retrievalQuery)
            when {
                hits == null -> logger("⚠️ RAG on, but retrieve() returned null (index empty or Ollama error) — answering without context")
                hits.isEmpty() -> logger("📚 RAG: 0 chunks matched")
                else -> {
                    val best = hits.maxOfOrNull { it.score } ?: 0f
                    logger("📚 RAG: ${hits.size} chunk(s), best similarity ${String.format("%.2f", best)}")
                }
            }
            hits
        } else null

        // День 24: анти-галлюцинация. Слабый контекст (max similarity < порога или 0 чанков) →
        // canned-response БЕЗ вызова LLM (дёшево, 100% отказ, persist'ится в history). Не триггерится
        // при ragContext=null (мягкая деградация дня 22 — Ollama down/пустой индекс → без RAG-блока,
        // не отказ). dontKnowThreshold=0.0 (default) → выключено, backward-compat с днём 23.
        if (ragContext != null && dontKnowThreshold > 0.0f) {
            val maxScore = ragContext.maxOfOrNull { it.score } ?: 0f
            val weak = ragContext.isEmpty() || maxScore < dontKnowThreshold
            if (weak) {
                logger("🚫 Anti-hallucination: best similarity ${String.format("%.2f", maxScore)} < threshold ${String.format("%.2f", dontKnowThreshold)} → canned «не знаю» (LLM не вызывается)")
                val canned = CannedResponses.weakContext(userMessage, maxScore, dontKnowThreshold)
                val cannedMsg = ChatMessage(role = "assistant", content = canned, parentId = userMsg.id)
                history.add(cannedMsg)
                memoryStore.saveMessage(chatId, cannedMsg)
                return canned
            }
        }
        if (ragContext != null) logger("🤖 Generating answer via LLM…")

        // Build messages
        val messagesToSend = buildMessagesToSend(userMsg, ragContext)
        val estimatedTokens = tokenCounter.estimateHistoryTokens(messagesToSend)
        if (estimatedTokens > contextLimit) {
            logger("⚠️ Warning: estimated $estimatedTokens tokens exceeds context limit ($contextLimit)")
        }

        // День 17: tool-use loop. tools = null (нет toolExecutor / MCP недоступен) → один shot,
        // поведение дней 1–16. Иначе LLM может вернуть tool_calls → исполняем → feed-back → финал.
        val tools = loadToolsOrNull()
        // День 24: сохраняем retrieved-контекст хода для пост-чека цитирования в finalizeAssistant.
        ragContextAtLastTurn = ragContext
        val result = runToolLoop(messagesToSend, OutputBudget.maxTokensFor(model, estimatedTokens), tools, userMsg)
        return result
    }

    /**
     * День 30 (streaming SSE): streaming-вариант [chat] для REPL обычного чата. Повторяет
     * пред-обработку [chat] (ensureLoaded, userMsg+save, RAG retrieve, anti-hallucination,
     * buildMessagesToSend, estimateTokens), но финальный LLM-вызов стримит токены через [onToken]
     * по мере генерации — пользователь видит контент сразу (40-90с «пустоты» thinking-модели исчезают).
     *
     * **MVP-ограничение:** стримим ТОЛЬКО свободный чат (tools==null). Если tools подключены
     * ([loadToolsOrNull] != null) — tool-итерации ([runToolLoop]) требуют полный response для
     * парсинга tool_calls, стриминг tool-аргументов не реализован в этом scope. В этом случае
     * делегируем в [runToolLoop] (batch-путь), но скармливаем финальный артефакт через [onToken]
     * целиком (caller видит ответ одним блоком — как раньше, без progressive). Это осознанный
     * компромисс: streaming нужен именно для долгих thinking-ответов свободного чата, tool-loops
     * обычно быстрее (короткие structured-ответы).
     *
     * @param userMessage текст пользователя (как [chat])
     * @param onToken suspend-колбэк на каждый [StreamChunk.Delta] (incremental content). Caller
     *   (REPL) печатает через [com.cliagent.cli.AppTerminal.streamPrint] — progressive render.
     * @return полный текст ответа (склеенный из всех Delta). Persist'ится в history (как [chat]).
     *
     * [CancellationException] НЕ глотается (AGENTS.md) — пробрасывается caller'у.
     */
    suspend fun chatStreamed(
        userMessage: String,
        onToken: suspend (String) -> Unit,
        onReasoning: (suspend (String) -> Unit)? = null,
    ): String {
        ensureLoaded()

        val lastMsgId = history.lastOrNull()?.id
        val userMsg = ChatMessage(
            role = "user",
            content = userMessage,
            parentId = lastMsgId
        )
        history.add(userMsg)
        memoryStore.saveMessage(chatId, userMsg)

        // Auto-compression — симметрично chat() (день 9).
        if (contextManager == null && historyCompressor != null) {
            val shouldCompress = history.size > historyCompressor.compressThreshold &&
                history.size % historyCompressor.compressThreshold == 0
            if (shouldCompress) {
                logger("🔄 Compressing history...")
                val existingSummary = memoryStore.loadSummary(chatId)
                val result = historyCompressor.compress(history, existingSummary)
                if (result.wasCompressed && result.summary != null) {
                    memoryStore.saveSummary(chatId, result.summary)
                    logger("✓ Compressed ${result.summarizedCount} messages (~${result.tokenEstimate} tokens in summary)")
                }
            }
        }

        // RAG-retrieval — симметрично chat() (день 22/25). День 34: тот же heuristic pre-filter.
        val ragContext = if (isRagEnabled() && shouldRetrieve(userMessage)) {
            val retrievalQuery = if (conversationalQuery) buildConversationQuery(userMessage) else userMessage
            val hits = ragRetriever?.retrieve(retrievalQuery)
            when {
                hits == null -> logger("⚠️ RAG on, but retrieve() returned null (index empty or Ollama error) — answering without context")
                hits.isEmpty() -> logger("📚 RAG: 0 chunks matched")
                else -> {
                    val best = hits.maxOfOrNull { it.score } ?: 0f
                    logger("📚 RAG: ${hits.size} chunk(s), best similarity ${String.format("%.2f", best)}")
                }
            }
            hits
        } else null

        // Anti-hallucination — симметрично chat() (день 24).
        if (ragContext != null && dontKnowThreshold > 0.0f) {
            val maxScore = ragContext.maxOfOrNull { it.score } ?: 0f
            val weak = ragContext.isEmpty() || maxScore < dontKnowThreshold
            if (weak) {
                logger("🚫 Anti-hallucination: best similarity ${String.format("%.2f", maxScore)} < threshold ${String.format("%.2f", dontKnowThreshold)} → canned «не знаю» (LLM не вызывается)")
                val canned = CannedResponses.weakContext(userMessage, maxScore, dontKnowThreshold)
                val cannedMsg = ChatMessage(role = "assistant", content = canned, parentId = userMsg.id)
                history.add(cannedMsg)
                memoryStore.saveMessage(chatId, cannedMsg)
                return canned
            }
        }
        if (ragContext != null) logger("🤖 Generating answer via LLM…")

        val messagesToSend = buildMessagesToSend(userMsg, ragContext)
        val estimatedTokens = tokenCounter.estimateHistoryTokens(messagesToSend)
        if (estimatedTokens > contextLimit) {
            logger("⚠️ Warning: estimated $estimatedTokens tokens exceeds context limit ($contextLimit)")
        }

        // MVP: tools подключены → tool-loop (batch). Свободный чат → streaming. См. KDoc.
        val tools = loadToolsOrNull()
        ragContextAtLastTurn = ragContext
        if (tools != null) {
            // Tool-loop возвращает финальный артефакт; скармливаем целиком через onToken (caller
            // видит одним блоком, без progressive — но persist/recordUsage корректны через runToolLoop).
            val result = runToolLoop(messagesToSend, OutputBudget.maxTokensFor(model, estimatedTokens), tools, userMsg)
            onToken(result)
            return result
        }

        // Свободный чат: streaming.
        val request = ChatRequest(
            model = model,
            messages = messagesToSend,
            temperature = temperature,
            // День 31: cross-provider sampling (CLI > config > null).
            topP = topP,
            topK = topK,
            seed = seed,
            stop = stop,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            maxTokens = OutputBudget.maxTokensFor(model, estimatedTokens),
            stream = true,
        )
        val fullContent = StringBuilder()
        var finalUsage: com.cliagent.llm.model.Usage? = null
        var finishReason: String? = null
        try {
            llmClient.chatStream(request).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Delta -> {
                        fullContent.append(chunk.content)
                        onToken(chunk.content)
                    }
                    is StreamChunk.Reasoning -> {
                        // Qwen3 thinking: reasoning идёт ДО content. Показываем progressive
                        // «размышления» приглушённым цветом (caller решает как рендерить).
                        onReasoning?.invoke(chunk.content)
                    }
                    is StreamChunk.Done -> {
                        finalUsage = chunk.usage
                        finishReason = chunk.finishReason
                    }
                    is StreamChunk.Error -> throw LlmCallException(
                        chunk.code,
                        if (chunk.message.isBlank()) "Streaming failed (code ${chunk.code})" else chunk.message
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // AGENTS.md: не глотать
        }

        // Мера B: обрыв по длине (finish_reason=length) — сигналим типизированной ошибкой, как runToolLoop.
        if (finishReason == "length") {
            throw LlmCallException.truncated(fullContent.toString())
        }

        // Persist assistant message + post-processing — симметрично finalizeAssistant (день 17).
        // Используем сам ChatMessage без tool_calls (свободный чат, tools=null).
        val assistantMsg = ChatMessage(role = "assistant", content = fullContent.toString(), parentId = userMsg.id)
        finalizeAssistant(assistantMsg, userMsg)
        tokenCounter.recordUsage(chatId, finalUsage)
        return fullContent.toString()
    }

    /**
     * День 17: tool-use loop. Отправляет запрос (с tools, если есть); если LLM просит tool_calls —
     * исполняет каждый через [toolExecutor], дописывает assistant(c tool_calls) + tool-result
     * сообщения в in-memory scratch (БЕЗ persist в history — иначе ломаем сериализацию/контекст и
     * раздуваем окно) и зовёт LLM снова. Финальный ответ (без tool_calls или при исчерпании
     * [maxToolRounds], день 20 — default 8) persist'ится через [finalizeAssistant].
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
                temperature = temperature,
                // День 31: cross-provider sampling (CLI > config > null).
                topP = topP,
                topK = topK,
                seed = seed,
                stop = stop,
                frequencyPenalty = frequencyPenalty,
                presencePenalty = presencePenalty,
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
                    if (calls.isNullOrEmpty() || rounds >= maxToolRounds) {
                        return finalizeAssistant(choice.message, userMsg)
                    }
                    // исполняем tool_calls; промежуточные сообщения — только в scratch (не в history)
                    scratch.add(choice.message)
                    for (tc in calls) {
                        val args = parseToolArgs(tc.function.arguments)
                        logger("🔧 Tool call: ${tc.function.name}${formatToolArgs(args)}")
                        val toolResult = execTool(tc.function.name, args)
                        // День 34: логируем результат tool-call — критично для отладки.
                        // LLM может вызывать tool без path (пустые args) → результат подскажет почему.
                        val preview = toolResult.take(120).replace("\n", " ")
                        logger("   → $preview")
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

        // День 24: пост-чек цитирования (анти-галлюцинации). Если был RAG-контекст — проверяем,
        // упомянуты ли источники и есть ли цитаты в ответе. Warning через logger (поверх спиннера),
        // НЕ блокирует и НЕ re-prompt — модель может не послушаться усиленный промпт, это дёшево
        // сигнализирует. citationLogger — для /rag eval и тестов (метрика покрытия).
        val lastCtx = ragContextAtLastTurn
        if (lastCtx != null && lastCtx.isNotEmpty()) {
            val cite = CitationDetector.detect(assistantContent, lastCtx)
            if (!cite.sourcesPresent) logger("⚠️ Citation check: источники не упомянуты в ответе (anti-hallucination warning)")
            if (!cite.citationsPresent) logger("⚠️ Citation check: цитаты отсутствуют в ответе (anti-hallucination warning)")
            citationLogger(cite)
        }

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

    /**
     * Schemas tools для запроса; null если toolExecutor нет/недоступен (graceful — без tools).
     *
     * День 21 (волна W3.2): per-stage tool-scoping. Tools (схемы ~1100 токенов) подключаются только
     * на стадиях, где они полезны — [TaskStage.EXECUTION] (основная работа) и [TaskStage.VALIDATION]
     * (read_file для проверки артефакта). На CLARIFY/PLANNING/DONE — `tools=null` (экономия токенов).
     * При отсутствии активной задачи (свободный чат, taskState==null) — старое поведение (tools
     * подключаются), 0 регрессий для дней 17–20.
     */
    private suspend fun loadToolsOrNull(): List<ToolDefinition>? {
        val executor = toolExecutor ?: return null
        // W3.2: tool-scoping по стадии активной задачи.
        val stage = getTaskState()?.stage
        if (stage != null && stage !in TOOL_SCOPED_STAGES) return null
        return try {
            executor.definitions().ifEmpty { null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger("⚠️ MCP tools unavailable: ${e.message}; proceeding without tools.")
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

    /**
     * Компактная сводка аргументов tool-call'а для лога: `key=value` через запятую. Длинные строковые
     * значения обрезаются до [MAX_ARG_LEN] символов (`…`-суффикс), чтобы не спамить вывод при больших
     * payload (например, содержимое отчёта в format_report/save_to_file). Null/пустые args → пустая строка.
     */
    private fun formatToolArgs(args: Map<String, Any?>): String {
        if (args.isEmpty()) return "(no args)"   // День 34: явный маркер пустых args для отладки
        return args.entries.joinToString(
            separator = ", ",
            prefix = "(",
            postfix = ")",
        ) { (k, v) ->
            val raw = when (v) {
                is String -> "\"${v.take(MAX_ARG_LEN)}${if (v.length > MAX_ARG_LEN) "…" else ""}\""
                is List<*> -> "[${v.size} item${if (v.size == 1) "" else "s"}]"
                is Map<*, *> -> "{${v.size} field${if (v.size == 1) "" else "s"}}"
                null -> "null"
                else -> v.toString()
            }
            "$k=$raw"
        }
    }

    private companion object {
        const val MAX_ARG_LEN = 40   // обрезка длинных строковых аргументов в логе tool-call'а
        /** W3.2: стадии, где tools окупаются (схемы ~1100 токенов тянутся только тут). */
        val TOOL_SCOPED_STAGES: Set<com.cliagent.state.TaskStage> = setOf(
            com.cliagent.state.TaskStage.EXECUTION,
            com.cliagent.state.TaskStage.VALIDATION,
        )
        // День 34 (refinement): heuristic pre-filter для RAG-retrieval.
        const val RAG_MIN_QUERY_CHARS = 20
        val STAGE_TRANSITION_PHRASES = setOf(
            "да", "нет", "y", "n", "yes", "no", "ок", "ok", "хорошо", "понятно",
            "переходим", "продолжай", "выполняй", "согласен", "подтверждаю", "утверждаю",
            "следующая", "дальше", "вперед", "next", "continue", "go", "done",
        )
    }

    /**
     * День 25: собирает запрос для retrieval из [userMessage] + контекста диалога (conversation-aware
     * retrieval, GAP-B). Обогащение идёт **до** эмбеддинга — сам `RagRetriever`/`QueryRewriter` не
     * меняются (backward-compat дней 22–24).
     *
     * Контекст склеивается из:
     *  1. **Цели диалога** (`WorkingMemory.currentTask`) — фрейм, в котором интерпретируются follow-up.
     *     Напр. цель «разобраться в RAG» + реплика «а сколько для этого нужно?» → эмбеддер «понимает»,
     *     что «этим» = RAG.
     *  2. **Последних 2 user-реплик** истории (исключая только что добавленный `userMsg`) — для
     *     разрешения анафоры («это», «тот», «он») и контекста уточняющих вопросов.
     *  3. **Текущего вопроса** — всегда последней строкой (наибольший вес для близкого match).
     *
     * Edge-cases:
     *  - Первый ход (история пуста, только `userMsg`) → без блока «Предыдущие вопросы» (no-op).
     *  - `currentTask == null` → без блока «Контекст задачи».
     *  - Длинная история → только 2 последние реплики (не раздуваем embedding, ~4 chars/token).
     */
    internal fun buildConversationQuery(userMessage: String): String {
        val parts = mutableListOf<String>()
        workingMemory?.currentTask?.takeIf { it.isNotBlank() }?.let { parts.add("Контекст задачи: $it") }
        // dropLast(1) убирает только что добавленный userMsg; takeLast(2) — ограничивает контекст.
        val priorUserTurns = history
            .filter { it.role == "user" }
            .dropLast(1)
            .takeLast(2)
            .map { it.content }
        if (priorUserTurns.isNotEmpty()) {
            parts.add("Предыдущие вопросы: ${priorUserTurns.joinToString(" | ")}")
        }
        parts.add("Текущий вопрос: $userMessage")
        return parts.joinToString("\n")
    }

    private suspend fun buildMessagesToSend(
        userMsg: ChatMessage,
        ragContext: List<ScoredChunk>? = null,
    ): List<ChatMessage> {
        // Precedence (доработка Day 13): активная задача → stage-промпт (поведение per stage);
        // иначе reasoningStrategy → иначе статический systemPrompt (поведение Day 1-13).
        val taskState = getTaskState()
        val baseSystem = when {
            taskState != null -> StagePromptTemplates.buildSystemMessage(taskState.stage, taskState.taskKind)
            reasoningStrategy != null -> PromptTemplates.buildSystemMessage(reasoningStrategy)
            else -> systemPrompt
        }
        // Слоёный system prompt: base + [long-term] + [working] + [retrieved]; пустые слои элизируются
        val system = PromptBuilder(
            baseSystem, longTermMemory, workingMemory, ragContext,
            retrievedInstructionOverride = retrievedInstructionOverride,
        ).build()

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

    // ── День 22: RAG-режим (toggle через `/rag on|off`) ──

    /** Активен ли RAG-режим агента (инъекция retrieved-чанков). null retriever → всегда false. */
    fun isRagEnabled(): Boolean = ragEnabled && ragRetriever != null

    /**
     * День 34 (refinement): heuristic pre-filter для RAG-retrieval.
     *
     * Пропускает retrieval для сообщений, которые **не являются** семантическими запросами к
     * базе знаний: короткие подтверждения, команды перехода стадии, свободный чат вне контекста.
     * Это устраняет загрязнение контекста и лишние embedding-вызовы (каждый retrieval = embed
     * + topK + rerank = 1-3 сек на cloud, 30+ сек на локальной Ollama).
     *
     * Эвристика (детерминированная, без LLM):
     *  1. Короткие команды перехода стадии («да», «переходим», «выполняй») → skip.
     *  2. Сообщения <20 символов → skip (типичные подтверждения).
     *  3. Длинные сообщения (≥20 символов) → retrieve (считаем их потенциально релевантными).
     *
     * Граничный случай: «как вернуть деньги за премиум?» (30 символов) — проходит фильтр,
     * т.к. длина ≥20. Короткое «да» (2 символа) — не проходит (команда перехода + короткое).
     */
    private fun shouldRetrieve(userMessage: String): Boolean {
        val msg = userMessage.trim()
        // 1. Явные команды перехода стадии — skip независимо от длины.
        if (msg.lowercase() in STAGE_TRANSITION_PHRASES) return false
        // 2. Короткие сообщения (<ragMinQueryChars) — skip.
        if (msg.length < ragMinQueryChars) return false
        // 3. Остальные — retrieve.
        return true
    }

    /** Включить/выключить RAG-режим в рантайме (`/rag on|off`). Нет retriever'а — noop. */
    fun setRagEnabled(enabled: Boolean) {
        ragEnabled = enabled
    }

    /**
     * День 25 (TDD-починка): runtime-toggle conversation-aware retrieval. Симметрия с [setRagEnabled] —
     * `/rag scenario` форсирует `true` (follow-up должны находить контекст) и восстанавливает в `finally`.
     */
    fun setConversationalQuery(enabled: Boolean) {
        conversationalQuery = enabled
    }

    /** День 25 (TDD): текущее состояние conversation-aware retrieval (для save/restore в сценарии). */
    fun isConversationalQuery(): Boolean = conversationalQuery

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
     * День 34 (stage/swarm интеграция): переключить toolExecutor по taskKind.
     *
     * TaskOrchestrator при старте задачи вызывает это после определения kind.
     *  - FILE_OP: заменяет на FileToolExecutor (с gate), без MCP filesystem.
     *  - null/другой: возвращает предыдущий executor (с MCP), либо null если был null.
     *
     * Безопасно: меняется только в начале задачи (до первого LLM-вызова на EXECUTION).
     */
    fun setToolExecutor(executor: ToolExecutor?) {
        toolExecutor = executor
    }

    /** Текущий toolExecutor (для диагностики и /mcp в REPL). */
    fun currentToolExecutor(): ToolExecutor? = toolExecutor

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
