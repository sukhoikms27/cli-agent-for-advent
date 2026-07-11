package com.cliagent.cli

import com.cliagent.agent.Agent
import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.ProfileExtractor
import com.cliagent.agent.StatefulAgent
import com.cliagent.agent.stage.IntentClassifier
import com.cliagent.agent.stage.TaskOrchestrator
import com.cliagent.agent.stage.UserIntent
import com.cliagent.config.ConfigRepository
import com.cliagent.context.ContextManager
import com.cliagent.context.HistoryCompressor
import com.cliagent.context.strategy.BranchingStrategy
import com.cliagent.context.strategy.ContextStrategyType
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.context.strategy.StickyFactsStrategy
import com.cliagent.context.strategy.SummaryStrategy
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmClientFactory
import com.cliagent.llm.model.ReasoningStrategy
import com.cliagent.llm.pricing.Pricing
import com.cliagent.memory.JsonChatStore
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.mcp.McpClient
import com.cliagent.mcp.McpException
import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import com.cliagent.state.TaskStateMachine
import com.cliagent.state.TransitionOutcome
import com.cliagent.state.InteractionMode
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantCategory
import com.cliagent.state.invariant.LlmInvariantChecker
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.runBlocking

class ChatCommand : CliktCommand(name = "chat", help = "Start interactive chat with LLM") {
    // День 25: nullable — переопределяет config.model ТОЛЬКО если передан явно (-m). Иначе effective-
    // модель резолвится из config (env CLI_AGENT_MODEL > config.json > "glm-5.1"). До этого хардкод
    // .default("glm-5.1") всегда перекрывал config.model → /config set model не работал без -m
    // (баг: баннер показывал provider из config, но модель из default-флага → "model not found").
    private val model by option("-m", "--model", help = "Model name (overrides config.model)")
    private val temperature by option("-t", "--temperature", help = "Temperature (0.0-2.0)").double().default(0.7)
    private val strategy by option("-s", "--strategy", help = "Reasoning: direct, step_by_step, meta_prompt, expert_group").default("direct")
    private val chat by option("-c", "--chat", help = "Chat ID (or 'new')").default("default")
    private val compress by option("--compress", help = "Enable auto-compression of history").flag()
    private val keepRecent by option("--keep-recent", help = "Keep last N messages uncompressed").int().default(10)
    private val contextStrategy by option("--context", help = "Context strategy: sliding, facts, summary, branch").default("sliding")
    private val autoProfile by option("--auto-profile", help = "Auto-extract user profile every N turns via LLM").flag()
    private val invariantsEnabled by option(
        "--invariants",
        envvar = "CLI_AGENT_INVARIANTS",
        help = "Enforce project invariants: refuse violating requests, retry violating responses"
    ).flag("--no-invariants")
    private val noColor by option("--no-color", help = "Disable colored output").flag()
    /**
     * День 21 (волна W1): режим роя. Заменяет прежний бинарный `--no-swarm`.
     * - `auto` (default) — адаптивный гейт: рой только на стадиях, где он окупается (PLANNING/
     *   EXECUTION/VALIDATION); CLARIFY/DONE — single-agent; TRIVIAL-задачи — весь пайплайн single.
     * - `on` — рой на всех стадиях (прежний дефолт; для дебага/сравнения).
     * - `off` — простые агенты везде (эквивалент прежнего `--no-swarm`).
     * Backward-compat: `--no-swarm` по-прежнему работает (→ off). env: `CLI_AGENT_SWARM_MODE`.
     */
    private val swarmMode: com.cliagent.agent.swarm.SwarmMode by option(
        "--swarm-mode",
        envvar = "CLI_AGENT_SWARM_MODE",
        help = "Swarm mode: auto (adaptive gate, default) | on (swarm everywhere) | off (simple agents)"
    ).convert { com.cliagent.agent.swarm.SwarmMode.fromString(it) }.default(com.cliagent.agent.swarm.SwarmMode.AUTO)
    /** Legacy alias `--no-swarm` → вынуждает [swarmMode] = OFF (0 регрессий CLI). */
    private val noSwarm by option("--no-swarm", help = "Disable swarm (alias for --swarm-mode off)").flag()

    /**
     * День 15 (п.4): кэшированная стадия для динамического лейбла спиннера.
     * `getTaskState()` — suspend, а render-лямбда спиннера non-suspend; обновляем перед каждым
     * `withSpinner` из REPL-цикла. null → «Thinking…».
     */
    private var currentSpinnerStage: TaskStage? = null

    /** День 15 (п.4): лейбл спиннера по текущей стадии. Never throws (safe для render-лямбды). */
    private fun spinnerLabel(): String = when (currentSpinnerStage) {
        null, TaskStage.CLARIFY -> if (currentSpinnerStage == null) "Thinking…" else "Clarifying…"
        TaskStage.PLANNING -> "Planning…"
        TaskStage.EXECUTION -> "Executing…"
        TaskStage.VALIDATION -> "Validating…"
        TaskStage.DONE -> "Finalizing…"
    }

    override fun run() = runBlocking {
        if (noColor) AppTerminal.disableColor()

        val config = try {
            ConfigRepository().load()
        } catch (e: IllegalStateException) {
            AppTerminal.err(e.message ?: "config error")
            return@runBlocking
        }

        val memoryStore = JsonChatStore()

        val chatId = when {
            chat == "new" -> memoryStore.createChat().id
            chat == "default" -> {
                val existing = memoryStore.listChats().firstOrNull()
                existing?.id ?: memoryStore.createChat().id
            }
            else -> {
                if (memoryStore.loadChat(chat) != null) chat
                else memoryStore.createChat().id
            }
        }

        // День 22: единый RAG-embedder на сессию (переиспользуется retrieval-агентом и командами /rag).
        // День 26: embedder живёт ВНЕ [AgentSession] — он shared across cloud↔local switches (Ollama
        // работает независимо от chat-провайдера). Создаётся один раз, закрывается в finally при выходе.
        // OllamaEmbeddingClient лёгкий (HttpClient без активного соединения до первого запроса).
        val ragEmbedder = com.cliagent.rag.embedding.OllamaEmbeddingClient(
            baseUrl = config.rag.embeddingBaseUrl,
            model = config.rag.embeddingModel,
        )

        // День 26: сессия собрана через buildSession (вынесено из run() для /local live-switch).
        // `var` — команда `/local on|off` пересоздаёт сессию на другом провайдере без рестарта REPL.
        var session = buildSession(config, memoryStore, chatId, ragEmbedder)
        printBanner(session, chatId)
        AppTerminal.println("Type /help for commands, /exit to quit")

        val repl = ReplEngine()

        try {
            while (true) {
                val input = repl.readLine() ?: break   // null = Ctrl+D
                if (input.isBlank()) continue

                when {
                input == "/exit" -> break
                input == "/help" -> printHelp()
                input == "/history" -> printHistory(session.agent)
                input == "/chats" -> printChats(memoryStore)
                input == "/stats" -> printStats(session.agent, session.model)
                input == "/cost" -> printCost(session.agent, session.model)
                input == "/summary" -> printSummary(session.agent)
                input == "/compress" -> manualCompress(session.agent)
                input == "/facts" -> printFacts(session.agent)
                input.startsWith("/local") -> {
                    // День 26: /local live-switch cloud↔Ollama. handleLocal возвращает новую сессию
                    // при on/off (null при status/smoke — без switch). Закрываем старую при замене.
                    val next = handleLocal(input, session, memoryStore, chatId, ragEmbedder)
                    if (next != null) {
                        session.close()
                        session = next
                        printBanner(session, chatId)
                    }
                }
                input.startsWith("/strategy") -> handleStrategy(input, session.agent, session.client, session.model, memoryStore, chatId, keepRecent)
                input.startsWith("/branch") -> handleBranch(input, session.agent)
                input.startsWith("/memory") -> handleMemory(input, session.agent)
                input.startsWith("/profile") -> handleProfile(input, session.agent, session.client, session.model)
                input.startsWith("/invariants") -> handleInvariants(input, session.agent)
                input.startsWith("/task") -> handleTask(input, session.agent, session.orchestrator)
                input.startsWith("/mode") -> handleMode(input, session.agent)
                input.startsWith("/mcp") -> handleMcp(input, session.config.mcp.filter { it.enabled })
                // День 28: /rag compare-local перехватываем ДО session.ragCommands.handle — нужен доступ
                // к savedCloudConfig (cloud-config до /local on) для fair local-vs-cloud сравнения.
                input.startsWith("/rag compare-local") -> handleCompareLocal(input, session)
                input.startsWith("/rag") -> session.ragCommands.handle(input)
                input.startsWith("/config") -> handleConfig(input)
                input == "/reset" -> {
                    session.agent.reset()
                    AppTerminal.ok("History, summary, facts, branches, working memory cleared.")
                }
                else -> {
                    // День 15 (progressive): каждый блок стадии печатается сразу через onEmit
                    // по мере готовности; onStageStart ставит лейбл спиннера per-stage.
                    dispatchFreeText(
                        input, session.agent, session.statefulAgent, session.orchestrator, session.intentClassifier,
                        onEmit = { block ->
                            AppTerminal.println()
                            AppTerminal.markdown(block)
                            AppTerminal.println()
                        },
                        onStageStart = { stage -> currentSpinnerStage = stage }
                    )
                    // День 27: бейдж модели-источника после ответа (бонус-тумблер нед.6). Одна точка
                    // после dispatchFreeText — покрывает оба пути (stage-flow + обычный чат), без
                    // дублирования в onEmit (stage-flow эміттит несколько блоков). opt-in через /local mark.
                    if (markProvider) {
                        AppTerminal.printProviderBadge(session.resolvedProvider, session.model)
                    }
                }
            }
            }
        } finally {
            // День 17/26: закрываем ресурсы текущей сессии (MCP toolExecutor) и shared RAG-embedder.
            session.close()
            // День 22: закрываем RAG-embedder (shared HttpClient) при выходе из REPL.
            runCatching { ragEmbedder.close() }
        }
    }

    /**
     * День 26: сборка [AgentSession] из [config] + CLI-флагов. Вынесено из `run()` (:110-251), чтобы
     * команда `/local` могла пересоздать сессию на другом провайдере без рестарта REPL. CLI-флаги
     * (-m, --temperature, --strategy, --compress, --swarm-mode и пр.) читаются из свойств класса —
     * они постоянны на время жизни REPL; меняется только [config] (provider/model/baseUrl/apiKey).
     *
     * `ragEmbedder` передаётся параметром: он shared across switches (создаётся один раз в `run()`),
     * не принадлежит сессии и не закрывается в [AgentSession.close].
     */
    private suspend fun buildSession(
        config: com.cliagent.config.AppConfig,
        memoryStore: MemoryStore,
        chatId: String,
        ragEmbedder: com.cliagent.rag.embedding.OllamaEmbeddingClient,
    ): AgentSession {
        // День 25: multi-provider dispatch через factory.
        val client = LlmClientFactory.create(config)
        val resolvedProvider = LlmClientFactory.resolveProvider(config)

        // День 25: effective-модель — флаг -m > config.model (env CLI_AGENT_MODEL > config.json) >
        // "glm-5.1". Флаг nullable, поэтому /config set model работает без -m.
        val model = model ?: config.model.ifBlank { "glm-5.1" }

        // День 30 (streaming SSE): вычисляем streaming-флаг. config.stream: "auto" (default) →
        // только Ollama (локальные thinking-модели — главный бенефициар; cloud z.ai и так быстрый);
        // "true" → всегда; "false" → никогда (поведение дней 1–29). env CLI_AGENT_STREAM override.
        streamEnabled = when (config.stream.lowercase().trim()) {
            "true" -> true
            "false" -> false
            else -> resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA   // auto
        }

        val reasoningStrategy = ReasoningStrategy.entries.find { it.label == strategy }
        val historyCompressor = if (compress) {
            HistoryCompressor(client, model, keepRecentCount = keepRecent)
        } else {
            null
        }

        val contextManager = createStrategy(contextStrategy, client, model, memoryStore, chatId, keepRecent)

        val profileExtractor = if (autoProfile) ProfileExtractor(client, model) else null

        val mcpServers = config.mcp.filter { it.enabled }
        val toolExecutor: com.cliagent.agent.ToolExecutor? = when {
            mcpServers.size >= 2 -> com.cliagent.mcp.CompositeMcpToolExecutor(
                servers = mcpServers,
                logger = AppTerminal::println,
            )
            mcpServers.size == 1 -> com.cliagent.mcp.McpToolExecutor(mcpServers.first().toTransport())
            else -> null
        }
        val mcpServerCount = mcpServers.size

        // Fallback: per-strategy файл дефолтной стратегии — если основной index.json пуст.
        val ragFallbackStore = when (config.rag.defaultStrategy) {
            "fixed" -> com.cliagent.rag.JsonRagStore(com.cliagent.config.AppPaths.ragIndexFixed)
            else -> com.cliagent.rag.JsonRagStore(com.cliagent.config.AppPaths.ragIndexStructural)
        }
        val ragRetriever = com.cliagent.rag.RagRetriever(
            embedder = ragEmbedder,
            topK = config.rag.topK,
            fallbackStore = ragFallbackStore,
            rewriter = if (config.rag.queryRewriter.lowercase() == "identity") null
                else com.cliagent.rag.queryRewriterOf(config.rag.queryRewriter, client, model),
            reranker = com.cliagent.rag.rerankerOf(config.rag.reranker, config.rag, client, model),
            candidatePoolSize = config.rag.candidatePoolSize,
        )

        val agent = ContextAwareAgent(
            llmClient = client,
            memoryStore = memoryStore,
            model = model,
            chatId = chatId,
            // День 29: prompt-адаптация для local RAG — task-specific system prompt для 14B моделей.
            // Локальные модели хуже следуют сложным инструкциям → явный, короткий промпт с жёстким
            // требованием источника. Включается когда provider=OLLAMA && RAG активен; cloud → default.
            systemPrompt = if (resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA && config.rag.enabled) {
                com.cliagent.llm.model.SystemPrompts.localRag
            } else {
                com.cliagent.llm.model.SystemPrompts.default
            },
            reasoningStrategy = reasoningStrategy,
            historyCompressor = historyCompressor,
            contextManager = contextManager,
            profileExtractor = profileExtractor,
            autoProfileEvery = if (autoProfile) 5 else 0,
            toolExecutor = toolExecutor,
            maxToolRounds = config.maxToolRounds,
            temperature = temperature,
            // День 29: contextLimit из registry (реальный context_length модели), не хардкод 128K.
            // qwen3:14b → 40960 (Ollama /api/tags), glm-5.1 → 200K. Корректный warning при overflow.
            contextLimit = com.cliagent.llm.ModelLimitsRegistry.forModel(model).contextWindow,
            logger = AppTerminal::println,
            ragRetriever = ragRetriever,
            ragEnabled = config.rag.enabled,
            dontKnowThreshold = config.rag.dontKnowThreshold,
            conversationalQuery = config.rag.conversationalQuery,
        )

        val checker = if (invariantsEnabled) LlmInvariantChecker(client, model) else null
        val statefulAgent = StatefulAgent(agent, checker) { agent.getInvariants() }
        val orchestrator = TaskOrchestrator(
            agent, client, model,
            swarmMode = if (noSwarm) com.cliagent.agent.swarm.SwarmMode.OFF else swarmMode,
            chat = { msg -> AppTerminal.withSpinner({ spinnerLabel() }) { statefulAgent.chat(msg) } }
        )

        val intentClassifier = IntentClassifier(client, model)

        val ragCommands = RagCommands(config.rag, ragEmbedder, agent,
            chat = { msg ->
                AppTerminal.withSpinner({ "RAG eval…" }) {
                    statefulAgent.chat(msg)
                }
            },
            ragRetriever = ragRetriever,
            llmClient = client,
            model = model,
        )

        return AgentSession(
            client = client,
            resolvedProvider = resolvedProvider,
            model = model,
            config = config,
            agent = agent,
            statefulAgent = statefulAgent,
            orchestrator = orchestrator,
            intentClassifier = intentClassifier,
            ragCommands = ragCommands,
            ragRetriever = ragRetriever,
            contextManager = contextManager,
            checker = checker,
            toolExecutor = toolExecutor,
            maxToolRounds = config.maxToolRounds,
            mcpServerCount = mcpServerCount,
        )
    }

    /**
     * День 26: баннер текущей сессии (провайдер/модель/контекст/MCP/RAG/…). Печатается при старте и
     * после `/local on|off` switch. CLI-флаги (compress/invariants/swarm) берутся из свойств класса.
     */
    private suspend fun printBanner(session: AgentSession, chatId: String) {
        val invariantsLabel = if (invariantsEnabled) "ON" else "OFF"
        val compressLabel = if (compress) "ON" else "OFF"
        val swarmLabel = if (noSwarm) "OFF" else swarmMode.label.uppercase()
        val modeLabel = (session.agent.getWorkingMemory()?.interactionMode ?: InteractionMode.PLAN).name.lowercase()
        val mcpLabel = if (session.mcpServerCount == 0) "OFF" else "${session.mcpServerCount} server(s)"
        val ragLabel = if (session.agent.isRagEnabled()) "ON" else "OFF"
        AppTerminal.println(
            "CLI Agent v0.9 | Chat: $chatId | Provider: ${session.resolvedProvider.id} | Model: ${session.model} | " +
                "Context: ${session.contextManager.getStrategy().getName()} | MCP: $mcpLabel | RAG: $ragLabel | " +
                "MaxToolRounds: ${session.maxToolRounds} | Compress: $compressLabel | Invariants: $invariantsLabel | " +
                "Swarm: $swarmLabel | Mode: $modeLabel"
        )
    }

    /**
     * День 26: сохранённый cloud-config для `/local off` (восстановление без повторной загрузки
     * config.json — env перебил бы overlay). null, пока не было `/local on`. Класс инстанцируется
     * clikt один раз на `chat`, поэтому это состояние валидно на время жизни REPL.
     */
    private var savedCloudConfig: com.cliagent.config.AppConfig? = null

    /**
     * День 27: runtime-toggle маркировки ответов моделью-источником (бонус-тумблер нед.6).
     * Default `false` (backward-compat — вывод не меняется для существующих cloud-only сессий).
     * Переключается через `/local mark on|off|status` (как [savedCloudConfig] — session-scoped var,
     * без persist в config.json: preference сессии). Бейдж печатается в REPL-цикле после ответа.
     */
    private var markProvider: Boolean = false

    /**
     * День 30 (streaming SSE): включён ли streaming-путь для REPL обычного чата. Вычисляется в
     * [buildSession] из config.stream + resolvedProvider (auto → только Ollama; cloud быстрый, MVP
     * не стримит). Пересчитывается на `/local` switch (сессия пересоздаётся, поле обновляется).
     * Streaming применяется только в [dispatchFreeText] для свободного чата (без активной задачи).
     */
    private var streamEnabled: Boolean = false

    /**
     * День 26: обработчик `/local` — live-switch cloud ↔ локальная Ollama без рестарта REPL.
     *
     * Подкоманды:
     * - `/local` | `/local status` — health-check Ollama (native /api/tags) + текущий provider/model.
     * - `/local on [model]` — сохранить текущий config как cloud (если ещё не сохранён), переключиться
     *   на overlay `{provider=ollama, baseUrl=http://localhost:11434/v1, model=qwen3:14b, apiKey=""}`,
     *   пересобрать сессию, persist provider=ollama в config.json.
     * - `/local off` — восстановить cloud-сессию из [savedCloudConfig], persist provider обратно.
     * - `/local smoke` — прогнать 3 промпта через прямой client.chat (без агента/history).
     *
     * @return новая [AgentSession] при on/off (caller закрывает старую и заменяет); null при
     *   status/smoke (без switch).
     */
    private suspend fun handleLocal(
        input: String,
        session: AgentSession,
        memoryStore: MemoryStore,
        chatId: String,
        ragEmbedder: com.cliagent.rag.embedding.OllamaEmbeddingClient,
    ): AgentSession? {
        val parts = input.trim().split("\\s+".toRegex())
        val sub = parts.getOrNull(1)?.lowercase()
        when (sub) {
            null, "status" -> {
                printLocalStatus(session)
                return null
            }
            "on" -> {
                val targetModel = parts.getOrNull(2) ?: "qwen3:14b"
                return switchToLocal(session, targetModel, memoryStore, chatId, ragEmbedder)
            }
            "off" -> {
                return switchToCloud(session, memoryStore, chatId, ragEmbedder)
            }
            "smoke" -> {
                runLocalSmoke(session)
                return null
            }
            "mark" -> {
                handleLocalMark(parts.getOrNull(2))
                return null
            }
            else -> {
                AppTerminal.println("Unknown /local command: $sub. Use: on [model], off, status, smoke, mark")
                return null
            }
        }
    }

    /** `/local` | `/local status` — health-check + текущий провайдер/модель. */
    private suspend fun printLocalStatus(session: AgentSession) {
        AppTerminal.println("🔌 Current session: provider=${session.resolvedProvider.id}, model=${session.model}")
        val nativeBase = com.cliagent.llm.nativeBaseFrom(session.config.baseUrl)
        val effectiveBase = if (session.resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA) nativeBase
            else "http://localhost:11434"
        val checker = com.cliagent.llm.OllamaHealthChecker(baseUrl = effectiveBase)
        try {
            val health = AppTerminal.withSpinner("Pinging Ollama…") { checker.checkHealth() }
            if (!health.reachable) {
                AppTerminal.warn("Ollama недоступна: ${health.errorMessage}")
                AppTerminal.println("   Запустите: ollama serve (HTTP на http://localhost:11434)")
                return
            }
            AppTerminal.ok("Ollama reachable на $effectiveBase (${health.models.size} models)")
            if (health.models.isNotEmpty()) {
                health.models.forEach { m ->
                    val sizeMb = if (m.sizeBytes > 0) " · ${m.sizeBytes / 1_048_576}MB" else ""
                    val q = m.quantization?.let { " · $it" } ?: ""
                    val p = m.parameterSize?.let { " · $it" } ?: ""
                    AppTerminal.println("  - ${m.name}$p$q$sizeMb")
                }
            } else {
                AppTerminal.println("  (no models installed; ollama pull qwen3:14b)")
            }
        } finally {
            checker.close()
        }
    }

    /**
     * `/local on [model]` — переключение на локальную Ollama. Сохраняет текущий config как cloud
     * (для будущего `/local off`), строит overlay, пересоздаёт сессию, persist provider=ollama.
     */
    private suspend fun switchToLocal(
        session: AgentSession,
        targetModel: String,
        memoryStore: MemoryStore,
        chatId: String,
        ragEmbedder: com.cliagent.rag.embedding.OllamaEmbeddingClient,
    ): AgentSession? {
        // Сохраним cloud-config при первом переключении (чтобы /local off знал, куда вернуться).
        if (savedCloudConfig == null) {
            savedCloudConfig = session.config
        }
        val overlay = session.config.copy(
            provider = "ollama",
            baseUrl = "http://localhost:11434/v1",
            model = targetModel,
            apiKey = "",
        )
        val newSession = try {
            buildSession(overlay, memoryStore, chatId, ragEmbedder)
        } catch (e: Throwable) {
            AppTerminal.err("Не удалось переключиться на локальную Ollama: ${e.message}")
            return null
        }
        // Persist выбора в config.json (env override может перебить при следующем load, но file
        // обновлён). runCatching — persist не должен ронять switch.
        runCatching { ConfigRepository().setLlmField("provider", "ollama") }
        val limits = com.cliagent.llm.ModelLimitsRegistry.forModel(targetModel)
        AppTerminal.ok("Switched to LOCAL: provider=ollama, model=$targetModel " +
            "(context ${limits.contextWindow / 1000}K, output ${limits.maxOutput / 1000}K)")
        return newSession
    }

    /** `/local off` — возврат к cloud-сессии из [savedCloudConfig]. */
    private suspend fun switchToCloud(
        session: AgentSession,
        memoryStore: MemoryStore,
        chatId: String,
        ragEmbedder: com.cliagent.rag.embedding.OllamaEmbeddingClient,
    ): AgentSession? {
        val cloud = savedCloudConfig
        if (cloud == null) {
            AppTerminal.warn("Нет сохранённого cloud-config. /local off доступен только после /local on.")
            return null
        }
        val newSession = try {
            buildSession(cloud, memoryStore, chatId, ragEmbedder)
        } catch (e: Throwable) {
            AppTerminal.err("Не удалось вернуться на cloud: ${e.message}")
            return null
        }
        savedCloudConfig = null
        runCatching { ConfigRepository().setLlmField("provider", cloud.provider) }
        AppTerminal.ok("Switched to CLOUD: provider=${LlmClientFactory.resolveProvider(cloud).id}, " +
            "model=${newSession.model}")
        return newSession
    }

    /** `/local smoke` — прямой прогон 3 промптов через client.chat (без агента/history). */
    private suspend fun runLocalSmoke(session: AgentSession) {
        // Переиспользуем session.client если уже local, иначе временный client на localhost:11434/v1.
        val client = if (session.resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA) {
            session.client
        } else {
            com.cliagent.llm.OpenAiCompatibleClient(
                baseUrl = "http://localhost:11434/v1",
                apiKey = "",
            )
        }
        val model = if (session.resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA) session.model else "qwen3:14b"
        AppTerminal.println("🧪 Smoke test: model=$model, baseUrl=http://localhost:11434/v1")
        LocalSmoke.runSmoke(client, model)
    }

    /**
     * День 27: `/local mark on|off|status` — runtime-toggle маркировки ответов моделью-источником.
     * on — после каждого ответа печатается бейдж (`🟡 [local:qwen3:14b]` / `🔵 [cloud:glm-5.1]`).
     * off (default) — backward-compat, вывод не меняется. status — текущее состояние.
     */
    private fun handleLocalMark(arg: String?) {
        when (arg?.lowercase()) {
            "on", "true", "1" -> {
                markProvider = true
                AppTerminal.ok("Provider marking ON — ответы помечаются бейджем модели-источника.")
            }
            "off", "false", "0" -> {
                markProvider = false
                AppTerminal.ok("Provider marking OFF")
            }
            "status", null -> {
                AppTerminal.println("Provider marking: ${if (markProvider) "ON" else "OFF"}")
            }
            else -> {
                AppTerminal.println("Usage: /local mark on|off|status")
            }
        }
    }

    /**
     * День 28: `/rag compare-local` — side-by-side сравнение local vs cloud LLM на ОДНОМ retrieved-
     * контексте (fair compare). Retrieval локальный (Ollama-эмбеддинги, общие для обоих клиентов);
     * генерация прогоняется напрямую через [LlmClient.chat] без агента/history.
     *
     * Проводит fair-сравнение: для каждого контрольного вопроса контекст извлекается ОДИН раз,
     * затем идентичный промпт скармливается local и (опционально) cloud клиенту. Метрики:
     * latency (скорость), keyword coverage + citation (качество), errors (стабильность).
     *
     * Источники клиентов:
     *  - local: session.client если provider=OLLAMA, иначе временный OpenAiCompatibleClient на
     *    http://localhost:11434/v1 с model=qwen3:14b (паттерн [runLocalSmoke]).
     *  - cloud: [savedCloudConfig] (cloud-config до `/local on`) если есть, иначе session.config
     *    если текущий провайдер — cloud; пытаемся [LlmClientFactory.create], при отсутствии API key
     *    → cloudClient=null (local-only режим).
     *
     * Ollama health-check через [com.cliagent.llm.OllamaHealthChecker]: если недоступна — warn + return
     * (retrieval невозможен без локальных эмбеддингов).
     */
    private suspend fun handleCompareLocal(input: String, session: AgentSession) {
        // День 28: опциональный аргумент N — число вопросов (ускоряет демо на медленных локальных
        // моделях: qwen3:14b ~30-60с/вопрос → 10 вопросов = 5-10 мин). `/rag compare-local 3` → первые 3.
        val arg = input.trim().split("\\s+".toRegex()).getOrNull(2)?.toIntOrNull()
        // 1) Загрузка eval-вопросов (classpath, по образцу RagCommands.loadEvalQuestions).
        val allQuestions = loadCompareQuestions()
        if (allQuestions.isEmpty()) {
            AppTerminal.warn("No eval questions found (rag/eval-questions.json).")
            return
        }
        val questions = if (arg != null && arg > 0) allQuestions.take(arg) else allQuestions
        if (arg != null && arg > 0) {
            AppTerminal.println("Limiting to first $arg of ${allQuestions.size} questions.")
        }

        // 2) Ollama health-check — retrieval требует локальные эмбеддинги.
        val nativeBase = com.cliagent.llm.nativeBaseFrom(session.config.baseUrl)
        val effectiveBase = if (session.resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA) nativeBase
            else "http://localhost:11434"
        val checker = com.cliagent.llm.OllamaHealthChecker(baseUrl = effectiveBase)
        try {
            val health = AppTerminal.withSpinner("Pinging Ollama…") { checker.checkHealth() }
            if (!health.reachable) {
                AppTerminal.warn("Ollama недоступна: ${health.errorMessage}")
                AppTerminal.println("   Compare-local требует Ollama для retrieval. Запустите: ollama serve")
                return
            }
            AppTerminal.ok("Ollama reachable на $effectiveBase (${health.models.size} models)")
        } finally {
            checker.close()
        }

        // 3) localClient: session.client если уже local, иначе временный на localhost:11434/v1.
        val localClient = if (session.resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA) {
            session.client
        } else {
            com.cliagent.llm.OpenAiCompatibleClient(
                baseUrl = "http://localhost:11434/v1",
                apiKey = "",
            )
        }
        val localModel = if (session.resolvedProvider == com.cliagent.llm.LlmProvider.OLLAMA) {
            session.model
        } else {
            "qwen3:14b"
        }

        // 4) cloudClient: savedCloudConfig (до /local on) → иначе session.config если cloud активен.
        val cloudConfig = savedCloudConfig
            ?: session.config.takeIf { session.resolvedProvider != com.cliagent.llm.LlmProvider.OLLAMA }
        val cloudClient: com.cliagent.llm.LlmClient? = if (cloudConfig != null) {
            try {
                LlmClientFactory.create(cloudConfig)
            } catch (e: IllegalArgumentException) {
                AppTerminal.warn("Cloud недоступен (${e.message?.take(80)}) — compare в local-only режиме.")
                null
            } catch (e: IllegalStateException) {
                AppTerminal.warn("Cloud недоступен (${e.message?.take(80)}) — compare в local-only режиме.")
                null
            }
        } else {
            AppTerminal.warn("Cloud недоступен (нет API key / savedCloudConfig) — compare в local-only режиме.")
            null
        }
        val cloudModel = cloudConfig?.model

        // 5) Прогон через harness (печать per-question + сводной таблицы внутри runCompare).
        AppTerminal.withSpinner("RAG compare-local…") {
            LocalRagCompare.runCompare(
                questions = questions,
                retriever = session.ragRetriever,
                localClient = localClient,
                localModel = localModel,
                cloudClient = cloudClient,
                cloudModel = cloudModel,
            )
        }
    }

    /**
     * День 28: загрузка eval-вопросов для compare-local. По образцу [RagCommands.loadEvalQuestions],
     * но возвращает публичный [LocalRagCompare.CompareQuestion] (вместо private EvalQuestion).
     * Classpath: `/rag/eval-questions.json`.
     */
    private fun loadCompareQuestions(): List<LocalRagCompare.CompareQuestion> {
        // Единый Json с ignoreUnknownKeys=true (AGENTS.md / паттерн RagCommands.loadEvalQuestions).
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val raw = runCatching {
            javaClass.getResourceAsStream("/rag/eval-questions.json")?.use { it.readBytes() }
        }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<LocalRagCompare.CompareQuestion>>(String(raw, Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    /**
     * Диспетчеризация свободного текста (не slash-команды) — единый тестируемый шов stage-потока
     * (день 15). Извлечён из `else`-ветки REPL, чтобы покрыть юнит-тестами логику без мока
     * терминала: DONE-reset → авто-определение интента → автостарт FSM → stage-поток / обычный чат.
     *
     * I/O (печать, спиннер) остаются здесь, но они test-safe: mordant non-TTY no-op (см.
     * MarkdownRenderTest), `withSpinner` под `runTest` не виснет. Возврат — markdown-строка для
     * печати; null — ничего не выводить.
     */
    internal suspend fun dispatchFreeText(
        input: String,
        agent: ContextAwareAgent,
        statefulAgent: StatefulAgent,
        orchestrator: TaskOrchestrator,
        intentClassifier: IntentClassifier,
        onEmit: suspend (String) -> Unit = {},
        onStageStart: (TaskStage) -> Unit = {}
    ): String? {
        var taskState = agent.getTaskState()
        val mode = agent.getWorkingMemory()?.interactionMode ?: InteractionMode.PLAN

        // День 15 (follow-up): завершённая задача (DONE) в режиме ≠ MANUAL — сбросить FSM,
        // чтобы новое сообщение прошло обычную классификацию интента (новая задача vs вопрос),
        // а не ушло как feedback в DoneStageAgent. MANUAL не трогаем — там FSM через /task.
        if (taskState != null && taskState.stage == TaskStage.DONE &&
            mode != InteractionMode.MANUAL
        ) {
            agent.setTaskState(null)
            taskState = null
        }

        // День 15 (progressive): сброс лейбла спиннера → «Thinking…» для classify/chat-ветки;
        // stage-флоу выставит свою стадию через onStageStart.
        currentSpinnerStage = null

        // День 15 (п.1): при отсутствии активной задачи и режиме ≠ MANUAL — авто-определение
        // интента. QUESTION → обычный чат; TASK → автостарт жизненного цикла FSM.
        if (taskState == null && mode != InteractionMode.MANUAL) {
            val intent = AppTerminal.withSpinner({ spinnerLabel() }) {
                intentClassifier.classify(input)
            }
            if (intent == UserIntent.TASK) {
                val w = agent.getWorkingMemory() ?: WorkingMemory()
                agent.setWorkingMemory(w.copy(currentTask = input))
                // Спиннер — в chat-делегате оркестратора (per LLM-вызов); блоки печатаются
                // progressive через onEmit. Возврат = сцепленная строка (caller в проде игнорирует).
                return orchestrator.startTask(input, mode, onEmit = onEmit, onStageStart = onStageStart)
            }
            // QUESTION → обычный чат ниже
        }

        // День 13 (авто-поток): при активной задаче свободный текст = подтверждение
        // перехода («да») или уточнение артефакта текущей стадии. Иначе — обычный чат.
        // День 15 (п.3): режим передаётся — AUTO авто-advance без подтверждения.
        val taskResponse = orchestrator.handleUserInput(input, mode, onEmit = onEmit, onStageStart = onStageStart)
        currentSpinnerStage = agent.getTaskState()?.stage
        if (taskResponse != null) return taskResponse

        // День 14/15: свободный чат через StatefulAgent (инварианты opt-in --invariants).
        // LLM-сбой (таймаут/HTTP) — раньше сворачивался в строку "Error: ..." и печатался как
        // обычный ответ; теперь ContextAwareAgent.chat бросает LlmCallException — ловим тут,
        // чтобы REPL не упал, а показал понятную ошибку.
        // День 24: withTimedSpinner — live-таймер в лейбле спиннера (каждые 120ms обновляется
        // HH:MM:SS); после ответа печатается серая строка длительности через printDuration.
        //
        // День 30 (streaming SSE): если streamEnabled (config.stream auto+Ollama / true) — streaming
        // путь: токены печатаются progressive через [AppTerminal.streamPrint] по мере генерации,
        // затем финальный markdown-рендер через [AppTerminal.streamFinalize]. Это убирает 40-90с
        // «пустоты» thinking-моделей (qwen3:14b). Fallback на batch+spinner если streamEnabled=false.
        if (streamEnabled) {
            val start = System.nanoTime()
            AppTerminal.println()   // пустая строка перед ответом (как onEmit делает)
            var sawReasoning = false
            val fullContent = try {
                statefulAgent.contextAware.chatStreamed(
                    input,
                    onToken = { delta -> AppTerminal.streamPrint(delta) },
                    onReasoning = { reasoning ->
                        // Qwen3 thinking: первый reasoning-фрейм → разделитель + серый курсив.
                        if (!sawReasoning) {
                            AppTerminal.println(gray("💭 thinking…"))
                            sawReasoning = true
                        }
                        AppTerminal.streamPrintReasoning(reasoning)
                    },
                )
            } catch (e: LlmCallException) {
                val msg = "⚠️ Ошибка запроса к LLM: ${e.message}"
                onEmit(msg)
                return msg
            }
            // Если был reasoning (thinking), content начинается с новой строки под ним.
            if (sawReasoning) {
                AppTerminal.println()   // закрываем reasoning-блок перед финальным ответом
            }
            AppTerminal.streamFinalize(fullContent)
            val elapsedMillis = (System.nanoTime() - start) / 1_000_000
            AppTerminal.printDuration(elapsedMillis)
            return fullContent
        }

        val timed = try {
            AppTerminal.withTimedSpinner(spinnerLabel()) { statefulAgent.chat(input) }
        } catch (e: LlmCallException) {
            val msg = "⚠️ Ошибка запроса к LLM: ${e.message}"
            onEmit(msg)
            return msg
        }
        onEmit(timed.value)
        AppTerminal.printDuration(timed.elapsedMillis)   // день 24: серая ⏱ HH:MM:SS
        return timed.value
    }

    private fun createStrategy(
        type: String,
        client: com.cliagent.llm.LlmClient,
        model: String,
        memoryStore: MemoryStore,
        chatId: String,
        keepRecent: Int
    ): ContextManager {
        val strategy = when (type.lowercase()) {
            "sliding" -> SlidingWindowStrategy(keepRecent)
            "facts" -> StickyFactsStrategy(client, model, keepRecent)
            "summary" -> {
                val compressor = HistoryCompressor(client, model, keepRecentCount = keepRecent)
                SummaryStrategy(compressor, memoryStore, chatId)
            }
            "branch" -> BranchingStrategy(memoryStore, chatId, keepRecent)
            else -> {
                AppTerminal.println("Unknown strategy '$type', using sliding window")
                SlidingWindowStrategy(keepRecent)
            }
        }
        return ContextManager(strategy)
    }

    private fun printHelp() {
        AppTerminal.println("""
            |CLI Agent v0.7 — Commands:
            |
            |  /help                — Show this help
            |  /history             — Show chat history
            |  /chats               — List saved chats
            |  /stats               — Token statistics for current session
            |  /cost                — Estimated cost for current session
            |  /summary             — Show current conversation summary
            |  /compress            — Manually trigger history compression
            |  /strategy [name]     — Switch context strategy (sliding, facts, summary, branch)
            |  /facts               — Show extracted facts (sticky-facts strategy)
            |  /branch create <name> [at <N>] — Create branch from message N
            |  /branch list         — List branches
            |  /branch switch <name> — Switch to branch
            |  /memory              — Show memory layers summary
            |  /memory show short   — Show current dialog (short-term)
            |  /memory show working — Show working memory (current task)
            |  /memory show long    — Show long-term memory (knowledge/decisions/profile)
            |  /memory save working task <text>     — Set current task
            |  /memory save working plan <text>     — Set plan
            |  /memory save working note <text>     — Append to scratch notes
            |  /memory save working decision <text> — Append a task decision
            |  /memory save long knowledge <key> <text> — Add/update knowledge (empty text removes)
            |  /memory save long decision <key> <text>  — Add/update decision (empty text removes)
            |  /memory clear working — Clear working memory for this chat
            |  /memory clear long    — Clear ALL long-term memory (global!)
            |  /profile              — Show user profile
            |  /profile set style <text>     — Set preferred answer style
            |  /profile set format <text>    — Set preferred format
            |  /profile set about <text>     — Set context (who you are, goal)
            |  /profile add constraint <text>  — Add a constraint (stack/ban/rule)
            |  /profile remove constraint <text> — Remove a constraint
            |  /profile extract      — Infer profile from current dialog via LLM
            |  /profile clear        — Clear the whole profile
            |  /invariants                — Show project invariants (hard rules the agent must not violate)
            |  /invariants add <cat> <id> <text> — Add invariant (cat: STACK/BAN/ARCH/BUSINESS)
            |  /invariants remove <id>    — Remove invariant by id
            |  /invariants clear          — Clear all invariants
            |  Note: --invariants enables runtime enforcement (request refusal + response retry).
            |        Without it, invariants are listed in the prompt but not enforced in code.
            |  /task                 — Show task state (stage/step/expected action/artifacts)
            |  /task start <text>    — Start a task: auto-selects first stage (clarify/planning),
            |                          generates its artifact via LLM, then asks to confirm advancing
            |  /task next            — Advance to next stage (manual escape hatch)
            |  /task set <stage>     — Set stage (clarify, planning, execution, validation, done).
            |                          Hard mode: illegal/artifact-missing transitions are blocked;
            |                          use --force (or -f) to override and jump.
            |  /task step <text>     — Set current step
            |  /task expect <text>   — Set expected action
            |  /task plan <text>     — Set approved plan (planning artifact)
            |  /task impl <text>     — Set implementation (execution artifact)
            |  /task verdict <text>  — Set verdict (validation artifact)
            |  /task back            — Revert one stage (by history)
            |  /task done            — Mark task done (advance from validation or force)
            |  /task reset           — Clear task state (FSM only; working memory kept)
            |  Note: after /task start the agent drives the flow — just type «да» to advance,
            |        or any other text to refine the current artifact. One stage = one agent;
            |        each plan step runs its own step-agent. /task next & /task set are escape hatches.
            |  /mode                 — Show interaction mode (manual/plan/auto)
            |  /mode <mode>          — Set mode: manual (chat-only, FSM via /task),
            |                          plan (default; stage-flow with confirmation),
            |                          auto (full automation; transitions without confirmation)
            |  /mcp                  — Show all MCP servers (status + tool counts)
            |  /mcp list-tools [name]— Connect & list a server's tools (or inline cmd)
            |  /mcp add <name> -- <cmd> <args…>     — Add stdio server to config.json
            |  /mcp add <name> --url <u> [--token]  — Add remote HTTP server to config.json
            |  /mcp remove <name>    — Remove server from config.json
            |  /mcp enable|disable <name> — Toggle server without removing (needs REPL restart)
            |  /rag                  — Show RAG status (embeddings model, current index)
            |  /rag index [fixed|structural] — Index document corpus (chunking + embeddings)
            |  /rag stats            — Show index statistics (chunks, tokens, dimension)
            |  /rag compare          — Build both strategies + compare stats + probe retrieval
            |  /rag compare-local [N] — Compare local vs cloud LLM on same retrieved context (N = questions)
            |  /rag search <query>   — Probe retrieval: top-5 chunks (smoke test, no agent)
            |  /rag config           — Show RAG configuration
            |  Note: RAG indexing requires Ollama running locally (ollama serve + pull nomic-embed-text).
            |  /config init          — Generate config.json from env/local.properties
            |  /config show          — Show config file summary
            |  /config path          — Print config file path
            |  /config set <f> <v>   — Set LLM field (provider, model, baseUrl, apiKey); restart to apply
            |  /local [status]      — Show Ollama health + current provider/model
            |  /local on [model]    — Switch to local Ollama (default qwen3:14b) without restart
            |  /local off           — Switch back to cloud (z.ai) after /local on
            |  /local smoke         — Run 3 prompts (arithmetic/reasoning/code) directly via LLM
            |  /local mark on|off   — Toggle provider badge after each answer (local/cloud marking)
            |  /reset               — Clear chat history, summary, facts, branches, working memory
            |  /exit                — Exit the program
            |
            |Context Strategies:
            |  sliding  — Keep last N messages, discard older (default)
            |  facts    — Extract key facts via LLM + keep last N messages
            |  summary  — Auto-summarize old messages via LLM + keep recent
            |  branch   — Create branches from checkpoints, switch between them
            |
            |CLI Flags:
            |  -m, --model <name>       — Model name (overrides config.model; default: glm-5.1 or /config set model)
            |  -t, --temperature <0-2>  — Sampling temperature (default: 0.7)
            |  -s, --strategy <type>    — Reasoning strategy (direct, step_by_step, meta_prompt, expert_group)
            |  -c, --chat <id|new>      — Chat session (default: continue last)
            |  --compress               — Enable auto-compression of history
            |  --keep-recent <N>        — Keep last N messages uncompressed (default: 10)
            |  --context <type>         — Context strategy (sliding, facts, summary, branch)
            |  --auto-profile           — Auto-extract user profile every 5 turns via LLM
            |  --no-color               — Disable colored output
        """.trimMargin())
    }

    private suspend fun printHistory(agent: Agent) {
        val history = agent.getHistory()
        if (history.isEmpty()) {
            AppTerminal.println("No messages yet.")
            return
        }
        history.forEachIndexed { i, msg ->
            val preview = if (msg.content.length > 100) msg.content.take(100) + "..." else msg.content
            AppTerminal.println("[${i + 1}] ${msg.role}: $preview")
        }
    }

    private suspend fun printChats(memoryStore: MemoryStore) {
        val chats = memoryStore.listChats()
        if (chats.isEmpty()) {
            AppTerminal.println("No saved chats.")
            return
        }
        chats.forEach { chat ->
            val tags = mutableListOf<String>()
            if (chat.summary != null) tags.add("summarized")
            if (chat.facts.isNotEmpty()) tags.add("${chat.facts.size} facts")
            if (chat.branches.isNotEmpty()) tags.add("${chat.branches.size} branches")
            val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""
            AppTerminal.println("${chat.id}  ${chat.title}  (${chat.messages.size} msgs, updated ${chat.updatedAt.take(19)})$tagStr")
        }
    }

    private fun printStats(agent: ContextAwareAgent, model: String) {
        val stats = agent.getTokenStats()
        val estimated = agent.getEstimatedHistoryTokens()
        if (stats == null) {
            AppTerminal.println("No token data yet. Send a message first.")
            return
        }
        val last = stats.lastRequestTokens
        val table = table {
            captionTop("📊 Token Statistics (session)")
            header { style(bold = true); row("Metric", "Value") }
            body {
                row("Requests", "${stats.requestCount}")
                row("Prompt", "${stats.totalPromptTokens} tokens")
                row("Completion", "${stats.totalCompletionTokens} tokens")
                row("Total", "${stats.totalTokens} tokens")
                row("Cached", "${stats.totalCachedTokens} tokens")
                row("Last request", "prompt=${last?.promptTokens ?: "?"} completion=${last?.completionTokens ?: "?"} total=${last?.totalTokens ?: "?"}")
                row("History est", "~$estimated tokens (approx)")
                row("Strategy", agent.getCurrentStrategyName())
            }
        }
        AppTerminal.println(table)
    }

    private fun printCost(agent: ContextAwareAgent, model: String) {
        val stats = agent.getTokenStats()
        if (stats == null) {
            AppTerminal.println("No token data yet. Send a message first.")
            return
        }
        val price = Pricing.getPrice(model)
        if (price == null) {
            AppTerminal.println("No pricing data for model '$model'. Token counts: prompt=${stats.totalPromptTokens} completion=${stats.totalCompletionTokens} total=${stats.totalTokens}")
            return
        }
        // День 27: локальная модель (Price(0,0)) бесплатна — показываем это явно вместо $0.00 везде.
        if (price.input == 0.0 && price.output == 0.0) {
            val table = table {
                captionTop("💰 Estimated Cost (session)")
                header { style(bold = true); row("Item", "Detail") }
                body {
                    row("Model", model)
                    row("Cost", "Local model — free (no API billing)")
                    row("Tokens", "prompt=${stats.totalPromptTokens} completion=${stats.totalCompletionTokens} total=${stats.totalTokens}")
                    row("Cached saved", "${stats.totalCachedTokens} tokens")
                }
            }
            AppTerminal.println(table)
            return
        }
        val inputCost = (stats.totalPromptTokens / 1_000_000.0) * price.input
        val outputCost = (stats.totalCompletionTokens / 1_000_000.0) * price.output
        val totalCost = inputCost + outputCost
        val table = table {
            captionTop("💰 Estimated Cost (session)")
            header { style(bold = true); row("Item", "Detail") }
            body {
                row("Model", model)
                row("Input", "${stats.totalPromptTokens} tokens × \$${String.format("%.2f", price.input)}/1M = \$${String.format("%.6f", inputCost)}")
                row("Output", "${stats.totalCompletionTokens} tokens × \$${String.format("%.2f", price.output)}/1M = \$${String.format("%.6f", outputCost)}")
                row("Total", "\$${String.format("%.6f", totalCost)}")
                row("Cached saved", "${stats.totalCachedTokens} tokens")
            }
        }
        AppTerminal.println(table)
    }

    private suspend fun printSummary(agent: ContextAwareAgent) {
        val summary = agent.getSummary()
        if (summary != null) {
            AppTerminal.println("📝 Conversation Summary:\n$summary")
        } else {
            AppTerminal.println("No summary yet. Use --compress flag or /compress command.")
        }
    }

    private suspend fun manualCompress(agent: ContextAwareAgent) {
        val summary = agent.compressNow()
        if (summary != null) {
            AppTerminal.ok("History compressed. Summary saved.")
        } else {
            AppTerminal.println("Compression not available. Start with --compress flag.")
        }
    }

    private fun printFacts(agent: ContextAwareAgent) {
        val cm = agent.getContextManager()
        val strategy = cm?.getStrategy() as? StickyFactsStrategy
        if (strategy != null) {
            val facts = strategy.getFacts()
            if (facts.isEmpty()) {
                AppTerminal.println("No facts extracted yet.")
            } else {
                AppTerminal.println("📋 Key Facts:")
                facts.forEach { (key, value) ->
                    AppTerminal.println("  $key: $value")
                }
            }
        } else {
            AppTerminal.println("Facts are only available with 'facts' strategy. Use /strategy facts or --context facts")
        }
    }

    private suspend fun handleStrategy(
        input: String,
        agent: ContextAwareAgent,
        client: com.cliagent.llm.LlmClient,
        model: String,
        memoryStore: MemoryStore,
        chatId: String,
        keepRecent: Int
    ) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            AppTerminal.println("Current strategy: ${agent.getCurrentStrategyName()}")
            AppTerminal.println("Available: sliding, facts, summary, branch")
            return
        }
        val name = parts[1].lowercase()
        val newManager = createStrategy(name, client, model, memoryStore, chatId, keepRecent)
        val msg = agent.switchStrategy(newManager)
        AppTerminal.println(msg)
    }

    private suspend fun handleBranch(input: String, agent: ContextAwareAgent) {
        val cm = agent.getContextManager()
        val strategy = cm?.getStrategy() as? BranchingStrategy
        if (strategy == null) {
            AppTerminal.println("Branching requires 'branch' strategy. Use /strategy branch or --context branch")
            return
        }

        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            AppTerminal.println("Usage: /branch create <name> [at <N>] | /branch list | /branch switch <name>")
            return
        }

        when (parts[1]) {
            "create" -> {
                if (parts.size < 3) {
                    AppTerminal.println("Usage: /branch create <name> [at <N>]")
                    return
                }
                val name = parts[2]
                val fromIndex = if (parts.size >= 5 && parts[3] == "at") {
                    parts[4].toIntOrNull() ?: (agent.getHistory().size - 1)
                } else {
                    agent.getHistory().size - 1
                }
                val branchId = strategy.createBranch(name, fromIndex)
                AppTerminal.ok("Created branch '$name' from message #${fromIndex + 1} (id: $branchId)")
            }
            "list" -> {
                val branches = strategy.listBranches()
                AppTerminal.println("Branches:")
                branches.forEach { AppTerminal.println("  $it") }
            }
            "switch" -> {
                if (parts.size < 3) {
                    AppTerminal.println("Usage: /branch switch <name>")
                    return
                }
                val name = parts[2]
                // Find branch by name
                val result = strategy.switchBranch(name)
                if (result.isSuccess) {
                    AppTerminal.ok("Switched to branch '${result.getOrDefault("")}'")
                } else {
                    AppTerminal.println("Branch '$name' not found")
                }
            }
            else -> AppTerminal.println("Unknown branch command: ${parts[1]}. Use: create, list, switch")
        }
    }

    private suspend fun handleMemory(input: String, agent: ContextAwareAgent) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            // Сводка по всем слоям
            val history = agent.getHistory()
            val working = agent.getWorkingMemory()
            val longTerm = agent.getLongTermMemory()
            AppTerminal.println("""
                |🧠 Memory layers:
                |  [short-term]  ${history.size} messages in current dialog
                |  [working]     ${if (working == null || working.isEmpty()) "empty" else "task='${working.currentTask ?: "-"}', ${working.taskDecisions.size} decisions"}
                |  [long-term]   ${longTerm.knowledge.size} knowledge, ${longTerm.decisions.size} decisions${if (longTerm.profile != null) ", profile set" else ""}
                |
                |Use: /memory show <short|working|long>, /memory save ..., /memory clear <working|long>
            """.trimMargin())
            return
        }

        when (parts[1]) {
            "show" -> {
                if (parts.size < 3) {
                    AppTerminal.println("Usage: /memory show <short|working|long>")
                    return
                }
                when (parts[2]) {
                    "short" -> printHistory(agent)
                    "working" -> {
                        val w = agent.getWorkingMemory()
                        if (w == null || w.isEmpty()) {
                            AppTerminal.println("Working memory is empty.")
                        } else {
                            AppTerminal.println("🔧 Working memory:")
                            w.currentTask?.let { AppTerminal.println("  Task: $it") }
                            w.plan?.let { AppTerminal.println("  Plan: $it") }
                            w.scratchNotes?.let { AppTerminal.println("  Notes: $it") }
                            if (w.taskDecisions.isNotEmpty()) {
                                AppTerminal.println("  Decisions:")
                                w.taskDecisions.forEach { AppTerminal.println("    - $it") }
                            }
                        }
                    }
                    "long" -> {
                        val lt = agent.getLongTermMemory()
                        AppTerminal.println("💾 Long-term memory:")
                        if (lt.knowledge.isNotEmpty()) {
                            AppTerminal.println("  Knowledge:")
                            lt.knowledge.forEach { (k, v) -> AppTerminal.println("    - $k: $v") }
                        }
                        if (lt.decisions.isNotEmpty()) {
                            AppTerminal.println("  Decisions:")
                            lt.decisions.forEach { (k, v) -> AppTerminal.println("    - $k: $v") }
                        }
                        val p = lt.profile
                        if (p != null) {
                            AppTerminal.println("  Profile:")
                            p.style?.let { AppTerminal.println("    Style: $it") }
                            p.format?.let { AppTerminal.println("    Format: $it") }
                            if (p.constraints.isNotEmpty()) p.constraints.forEach { AppTerminal.println("    Constraint: $it") }
                        }
                        if (lt.isEmpty()) AppTerminal.println("  (empty)")
                    }
                    else -> AppTerminal.println("Unknown layer: ${parts[2]}. Use: short, working, long")
                }
            }
            "save" -> handleMemorySave(parts, agent)
            "clear" -> {
                if (parts.size < 3) {
                    AppTerminal.println("Usage: /memory clear <working|long>")
                    return
                }
                when (parts[2]) {
                    "working" -> {
                        agent.setWorkingMemory(WorkingMemory())
                        AppTerminal.ok("Working memory cleared for this chat.")
                    }
                    "long" -> {
                        AppTerminal.warn("Clearing ALL long-term memory (global, affects every chat).")
                        agent.setLongTermMemory(LongTermMemory())
                        AppTerminal.ok("Long-term memory cleared.")
                    }
                    else -> AppTerminal.println("Unknown layer: ${parts[2]}. Use: working, long")
                }
            }
            else -> AppTerminal.println("Unknown /memory command: ${parts[1]}. Use: show, save, clear")
        }
    }

    private suspend fun handleMemorySave(parts: List<String>, agent: ContextAwareAgent) {
        // /memory save <working|long> <field> [args...]
        if (parts.size < 4) {
            AppTerminal.println("Usage: /memory save working <task|plan|note|decision> <text>")
            AppTerminal.println("       /memory save long <knowledge|decision> <key> <text>")
            return
        }
        when (parts[2]) {
            "working" -> {
                val field = parts[3]
                val text = parts.drop(4).joinToString(" ").trim()
                if (text.isEmpty() && field !in listOf("task", "plan")) {
                    AppTerminal.println("Text is required for '$field'.")
                    return
                }
                val w = agent.getWorkingMemory() ?: WorkingMemory()
                val updated = when (field) {
                    "task" -> w.copy(currentTask = text.ifEmpty { null })
                    "plan" -> w.copy(plan = text.ifEmpty { null })
                    "note" -> w.copy(scratchNotes = listOfNotNull(w.scratchNotes, text)
                        .filter { it.isNotBlank() }.joinToString("\n"))
                    "decision" -> w.copy(taskDecisions = w.taskDecisions + text)
                    else -> { AppTerminal.println("Unknown working field: $field. Use: task, plan, note, decision"); return }
                }
                agent.setWorkingMemory(updated)
                AppTerminal.ok("Working memory '$field' updated.")
            }
            "long" -> {
                val field = parts[3]              // knowledge | decision
                if (parts.size < 5) {
                    AppTerminal.println("Usage: /memory save long $field <key> <text>")
                    return
                }
                val key = parts[4]
                val text = parts.drop(5).joinToString(" ").trim()
                val lt = agent.getLongTermMemory()
                val updated = when (field) {
                    "knowledge" -> {
                        val map = lt.knowledge.toMutableMap()
                        if (text.isEmpty()) map.remove(key) else map[key] = text
                        lt.copy(knowledge = map)
                    }
                    "decision" -> {
                        val map = lt.decisions.toMutableMap()
                        if (text.isEmpty()) map.remove(key) else map[key] = text
                        lt.copy(decisions = map)
                    }
                    else -> { AppTerminal.println("Unknown long field: $field. Use: knowledge, decision"); return }
                }
                agent.setLongTermMemory(updated)
                AppTerminal.ok("Long-term '$field' ${if (text.isEmpty()) "removed '$key'" else "updated '$key'"}.")
            }
            else -> AppTerminal.println("Unknown layer: ${parts[2]}. Use: working, long")
        }
    }

    private suspend fun handleProfile(
        input: String,
        agent: ContextAwareAgent,
        client: com.cliagent.llm.LlmClient,
        model: String
    ) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2 || parts[1] == "show") {
            val p = agent.getProfile()
            if (p == null || p.isEmpty()) {
                AppTerminal.println("👤 User profile is empty. Use: /profile set style|format|about <text>, /profile add constraint <text>")
            } else {
                AppTerminal.println("👤 User profile:")
                p.style?.let { AppTerminal.println("  Style: $it") }
                p.format?.let { AppTerminal.println("  Format: $it") }
                p.about?.let { AppTerminal.println("  About: $it") }
                if (p.constraints.isNotEmpty()) {
                    AppTerminal.println("  Constraints:")
                    p.constraints.forEach { AppTerminal.println("    - $it") }
                }
            }
            return
        }

        when (parts[1]) {
            "set" -> {
                if (parts.size < 4) {
                    AppTerminal.println("Usage: /profile set <style|format|about> <text>")
                    return
                }
                val field = parts[2]
                val text = parts.drop(3).joinToString(" ").trim()
                if (text.isEmpty()) {
                    AppTerminal.println("Text is required.")
                    return
                }
                val cur = agent.getProfile() ?: UserProfile()
                val updated = when (field) {
                    "style" -> cur.copy(style = text)
                    "format" -> cur.copy(format = text)
                    "about" -> cur.copy(about = text)
                    else -> { AppTerminal.println("Unknown field: $field. Use: style, format, about"); return }
                }
                agent.setProfile(updated)
                AppTerminal.ok("Profile '$field' updated.")
            }
            "add" -> {
                if (parts.size < 4 || parts[2] != "constraint") {
                    AppTerminal.println("Usage: /profile add constraint <text>")
                    return
                }
                val text = parts.drop(3).joinToString(" ").trim()
                val cur = agent.getProfile() ?: UserProfile()
                agent.setProfile(cur.copy(constraints = cur.constraints + text))
                AppTerminal.ok("Constraint added.")
            }
            "remove" -> {
                if (parts.size < 4 || parts[2] != "constraint") {
                    AppTerminal.println("Usage: /profile remove constraint <text>")
                    return
                }
                val text = parts.drop(3).joinToString(" ").trim()
                val cur = agent.getProfile() ?: UserProfile()
                val filtered = cur.constraints.filterNot { it == text || it.contains(text) }
                if (filtered.size == cur.constraints.size) {
                    AppTerminal.println("No matching constraint found.")
                } else {
                    agent.setProfile(cur.copy(constraints = filtered))
                    AppTerminal.ok("Constraint removed.")
                }
            }
            "extract" -> {
                val history = agent.getHistory()
                if (history.isEmpty()) {
                    AppTerminal.println("No dialog yet to infer profile from.")
                    return
                }
                AppTerminal.println("🔄 Inferring profile from dialog...")
                val extractor = ProfileExtractor(client, model)
                val cur = agent.getProfile()
                val merged = AppTerminal.withSpinner("Inferring profile…") { extractor.extract(history, cur) }
                agent.setProfile(merged)
                AppTerminal.ok("Profile inferred and merged. Use /profile to view.")
            }
            "clear" -> {
                agent.setProfile(null)
                AppTerminal.ok("Profile cleared.")
            }
            else -> AppTerminal.println("Unknown /profile command: ${parts[1]}. Use: show, set, add, remove, extract, clear")
        }
    }

    // ── /invariants: жёсткие правила проекта (день 14, третий столп недели 3) ──

    private suspend fun handleInvariants(input: String, agent: ContextAwareAgent) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2 || parts[1] == "show") {
            // /invariants  |  /invariants show
            val list = agent.getInvariants()
            if (list.isEmpty()) {
                AppTerminal.println("🔒 No project invariants. Use: /invariants add <category> <id> <rule>")
            } else {
                AppTerminal.println("🔒 Project invariants:")
                list.forEach {
                    AppTerminal.println("  [${it.category.name.lowercase()}] ${it.id}: ${it.rule}")
                }
            }
            return
        }

        when (parts[1]) {
            "add" -> {
                // /invariants add <STACK|BAN|ARCH|BUSINESS> <id> <rule text...>
                if (parts.size < 5) {
                    AppTerminal.println("Usage: /invariants add <STACK|BAN|ARCH|BUSINESS> <id> <rule text>")
                    return
                }
                val category = try {
                    InvariantCategory.valueOf(parts[2].uppercase())
                } catch (e: IllegalArgumentException) {
                    AppTerminal.println("Unknown category: ${parts[2]}. Use: STACK, BAN, ARCH, BUSINESS")
                    return
                }
                val id = parts[3]
                val rule = parts.drop(4).joinToString(" ").trim()
                agent.addInvariant(Invariant(id = id, rule = rule, category = category))
                AppTerminal.ok("Invariant added: [${category.name.lowercase()}] $id")
            }
            "remove" -> {
                if (parts.size < 3) {
                    AppTerminal.println("Usage: /invariants remove <id>")
                    return
                }
                val removed = agent.removeInvariant(parts[2])
                if (removed) AppTerminal.ok("Removed invariant: ${parts[2]}")
                else AppTerminal.warn("Invariant not found: ${parts[2]}")
            }
            "clear" -> {
                agent.setInvariants(emptyList())
                AppTerminal.ok("All project invariants cleared.")
            }
            else -> AppTerminal.println("Unknown /invariants command: ${parts[1]}. Use: show, add, remove, clear")
        }
    }

    // ── /mcp: оркестрация MCP-серверов (день 16 → день 20 multi-server) ──

    /**
     * Multi-server UX (день 20). Команды:
     * - `/mcp` — сводка всех серверов (имя, транспорт, кол-во tools, статус) с live discovery.
     * - `/mcp add <name> -- <cmd> <args…>` — добавить stdio-сервер в config.json (путь B).
     * - `/mcp add <name> --url <u> [--token <t>]` — добавить remote-сервер в config.json.
     * - `/mcp remove <name>` — убрать сервер из config.json.
     * - `/mcp list-tools [name|<cmd…>]` — list-tools: конкретный сервер по имени, или inline-override.
     *
     * [servers] — текущий snapshot `config.mcp` (из [com.cliagent.config.AppConfig]). Add/remove
     * пишут в config.json через [ConfigRepository]; изменения вступают в силу после перезапуска REPL
     * (toolExecutor собран на старте сессии).
     */
    internal suspend fun handleMcp(
        input: String,
        servers: List<com.cliagent.mcp.McpServerConfig>,
    ) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            // /mcp — сводка серверов
            printMcpSummary(servers)
            return
        }
        when (parts[1]) {
            "add" -> handleMcpAdd(parts)
            "remove", "rm", "delete" -> handleMcpRemove(parts)
            "enable", "on" -> handleMcpToggle(parts, enabled = true)
            "disable", "off" -> handleMcpToggle(parts, enabled = false)
            "list-tools" -> handleMcpListTools(parts, servers)
            else -> AppTerminal.println(
                "Unknown /mcp command: ${parts[1]}. Use: add, remove, enable, disable, list-tools"
            )
        }
    }

    /** `/mcp` — сводка всех серверов с live discovery (connect + count tools). */
    private suspend fun printMcpSummary(servers: List<com.cliagent.mcp.McpServerConfig>) {
        if (servers.isEmpty()) {
            AppTerminal.println("🔌 MCP: no servers configured.")
            AppTerminal.println("   /mcp add <name> -- <command> <args…>   (stdio server)")
            AppTerminal.println("   /mcp add <name> --url <url> [--token]  (remote HTTP server)")
            AppTerminal.println("   Or edit ${com.cliagent.config.AppPaths.configFile}")
            return
        }
        AppTerminal.println("🔌 MCP servers (${servers.size}):")
        for (server in servers) {
            if (!server.enabled) {
                AppTerminal.println("  ⊘ ${server.name}  [disabled]")
                continue
            }
            val (status, toolCount) = try {
                val exec = com.cliagent.mcp.McpToolExecutor(server.toTransport())
                val count = runCatching {
                    exec.definitions().size
                }.getOrDefault(-1)
                runCatching { exec.close() }
                "✓" to count
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                "✗ ${e.message}" to 0
            }
            AppTerminal.println("  $status ${server.name}  (${server.transportLabel()}) — $toolCount tools")
        }
        AppTerminal.println("   /mcp list-tools <name> — list a server's tools")
        AppTerminal.println("   /mcp add/remove <name> — manage servers in config.json")
        AppTerminal.println("   /mcp enable|disable <name> — toggle without removing")
    }

    /** `/mcp add` — stdio (`-- <cmd> <args…>`) или remote (`--url <u> [--token <t>]`). */
    private fun handleMcpAdd(parts: List<String>) {
        // Формат: /mcp add <name> -- <cmd> <args…>   |   /mcp add <name> --url <u> [--token <t>]
        if (parts.size < 3) {
            AppTerminal.err("/mcp add <name> -- <command> <args…>   (stdio)")
            AppTerminal.err("/mcp add <name> --url <url> [--token <token>]   (remote)")
            return
        }
        val name = parts[2]
        val dashIndex = parts.indexOf("--")
        val server = when {
            parts.contains("--url") -> {
                val urlIdx = parts.indexOf("--url")
                val url = parts.getOrNull(urlIdx + 1)
                if (url.isNullOrBlank()) {
                    AppTerminal.err("Missing URL after --url.")
                    return
                }
                val token = parts.getOrNull(parts.indexOf("--token") + 1)
                    ?.takeIf { parts.contains("--token") }
                com.cliagent.mcp.McpServerConfig(name = name, url = url, token = token)
            }
            dashIndex >= 0 && parts.size > dashIndex + 1 -> {
                val cmdParts = parts.drop(dashIndex + 1)
                com.cliagent.mcp.McpServerConfig(name = name, command = cmdParts.first(), args = cmdParts.drop(1))
            }
            else -> {
                AppTerminal.err("Expected '--' before command, or '--url <url>'. Got: ${parts.drop(2).joinToString(" ")}")
                return
            }
        }
        try {
            ConfigRepository().addMcpServer(server)
            AppTerminal.ok("Server '$name' added to ${com.cliagent.config.AppPaths.configFile}. Restart REPL to apply.")
        } catch (e: Throwable) {
            AppTerminal.err("Failed to add server: ${e.message}")
        }
    }

    /** `/mcp remove <name>`. */
    private fun handleMcpRemove(parts: List<String>) {
        if (parts.size < 3) {
            AppTerminal.err("Usage: /mcp remove <name>")
            return
        }
        val name = parts[2]
        try {
            val removed = ConfigRepository().removeMcpServer(name)
            if (removed) {
                AppTerminal.ok("Server '$name' removed from ${com.cliagent.config.AppPaths.configFile}. Restart REPL to apply.")
            } else {
                AppTerminal.warn("Server '$name' not found in config.json.")
            }
        } catch (e: Throwable) {
            AppTerminal.err("Failed to remove server: ${e.message}")
        }
    }

    /** `/mcp enable <name>` / `/mcp disable <name>` — toggle без удаления записи. */
    private fun handleMcpToggle(parts: List<String>, enabled: Boolean) {
        val action = if (enabled) "enable" else "disable"
        if (parts.size < 3) {
            AppTerminal.err("Usage: /mcp $action <name>")
            return
        }
        val name = parts[2]
        try {
            val changed = ConfigRepository().setMcpServerEnabled(name, enabled)
            val state = if (enabled) "enabled" else "disabled"
            when {
                changed -> AppTerminal.ok("Server '$name' $state in ${com.cliagent.config.AppPaths.configFile}. Restart REPL to apply.")
                else -> AppTerminal.warn("Server '$name' not found (or already $state) in config.json.")
            }
        } catch (e: Throwable) {
            AppTerminal.err("Failed to $action server: ${e.message}")
        }
    }

    /** `/mcp list-tools [name|<cmd…>]` — конкретный сервер по имени, или inline stdio-override. */
    private suspend fun handleMcpListTools(
        parts: List<String>,
        servers: List<com.cliagent.mcp.McpServerConfig>,
    ) {
        // inline-override: /mcp list-tools npx -y … → временный stdio-сервер (Day 16 совместимость)
        val inline = parts.drop(2)
        if (inline.isNotEmpty() && inline.first() !in servers.map { it.name }) {
            listToolsInline(inline)
            return
        }
        val name = inline.firstOrNull()
        val server = servers.firstOrNull { it.name == name }
            ?: servers.firstOrNull()
            ?: run {
                AppTerminal.warn("No MCP server configured. Use: /mcp add <name> -- <command> <args…>")
                return
            }
        val endpoint = server.transportLabel()
        var client: McpClient? = null
        try {
            client = McpClient(server.toTransport())
            AppTerminal.println("🔌 Connecting to MCP server '${server.name}': $endpoint")
            AppTerminal.withSpinner("Connecting to MCP server…") { client.connect() }
            val tools = client.listTools()
            if (tools.isEmpty()) {
                AppTerminal.println("Server '${server.name}' exposed 0 tools.")
                return
            }
            val tbl = table {
                captionTop("🔌 MCP Tools (${tools.size}) — ${server.name}")
                header { style(bold = true); row("#", "Name", "Description") }
                body {
                    tools.forEachIndexed { i, t ->
                        row("${i + 1}", t.name, truncateDesc(t.description))
                    }
                }
            }
            AppTerminal.println(tbl)
        } catch (e: McpException) {
            AppTerminal.err("MCP failed: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppTerminal.err("MCP failed: ${e.message}")
        } finally {
            client?.let { runCatching { it.close() } }
        }
    }

    /** Inline list-tools без конфигурации (Day 16 совместимость): `/mcp list-tools npx -y …`. */
    private suspend fun listToolsInline(command: List<String>) {
        val endpoint = command.joinToString(" ")
        var client: McpClient? = null
        try {
            client = McpClient(com.cliagent.mcp.McpTransportConfig.Stdio(command))
            AppTerminal.println("🔌 Connecting to MCP server: $endpoint")
            AppTerminal.withSpinner("Connecting to MCP server…") { client.connect() }
            val tools = client.listTools()
            if (tools.isEmpty()) {
                AppTerminal.println("Server exposed 0 tools.")
                return
            }
            val tbl = table {
                captionTop("🔌 MCP Tools (${tools.size})")
                header { style(bold = true); row("#", "Name", "Description") }
                body {
                    tools.forEachIndexed { i, t ->
                        row("${i + 1}", t.name, truncateDesc(t.description))
                    }
                }
            }
            AppTerminal.println(tbl)
        } catch (e: McpException) {
            AppTerminal.err("MCP failed: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppTerminal.err("MCP failed: ${e.message}")
        } finally {
            client?.let { runCatching { it.close() } }
        }
    }

    // ── /config: управление config.json (день 20) ──

    /**
     * `/config init` — сгенерировать стартовый config.json из env/properties (Р3 миграция).
     * `/config show` — показать путь + краткую сводку файла. `/config path` — только путь.
     */
    private fun handleConfig(input: String) {
        val parts = input.trim().split("\\s+".toRegex())
        val sub = parts.getOrNull(1)
        when (sub) {
            "init" -> {
                val repo = ConfigRepository()
                val created = try {
                    repo.initFromLegacy()
                } catch (e: Throwable) {
                    AppTerminal.err("Failed: ${e.message}")
                    return
                }
                if (created) {
                    AppTerminal.ok("Created ${com.cliagent.config.AppPaths.configFile} from current env/local.properties.")
                    AppTerminal.println("Restart REPL to load it.")
                } else {
                    AppTerminal.warn("${com.cliagent.config.AppPaths.configFile} already exists; remove it first to regenerate.")
                }
            }
            "path" -> AppTerminal.println("${com.cliagent.config.AppPaths.configFile}")
            "show" -> {
                AppTerminal.println("Config file: ${com.cliagent.config.AppPaths.configFile}")
                val cfg = ConfigRepository().loadConfigFile()
                val exists = java.nio.file.Files.exists(com.cliagent.config.AppPaths.configFile)
                if (!exists) {
                    AppTerminal.println("  (file does not exist; config from env/local.properties)")
                    return
                }
                AppTerminal.println("  provider: ${cfg.provider.ifBlank { "(auto-detect)" }}")
                AppTerminal.println("  model: ${cfg.model.ifBlank { "(default)" }}")
                AppTerminal.println("  baseUrl: ${cfg.baseUrl.ifBlank { "(default)" }}")
                AppTerminal.println("  maxToolRounds: ${cfg.maxToolRounds}")
                AppTerminal.println("  apiKey: ${if (cfg.apiKey.isNullOrBlank()) "(absent in file)" else "***set***"}")
                AppTerminal.println("  mcp servers: ${cfg.mcp.size}")
                cfg.mcp.forEach { AppTerminal.println("    - ${it.name} [${if (it.enabled) "enabled" else "disabled"}]: ${it.transportLabel()}") }
            }
            "set" -> {
                // /config set <field> <value...> — value может содержать пробелы (e.g. имя модели).
                val setParts = input.removePrefix("/config set").trim().split(Regex("\\s+"), limit = 2)
                if (setParts.size < 2 || setParts.any { it.isBlank() }) {
                    AppTerminal.println("Usage: /config set <field> <value>  (fields: provider, model, baseUrl, apiKey)")
                    AppTerminal.println("Example: /config set provider ollama")
                } else {
                    val field = setParts[0]
                    val value = setParts[1]
                    try {
                        val updated = ConfigRepository().setLlmField(field, value)
                        AppTerminal.ok("$field updated → '$value'")
                        AppTerminal.warn("Restart chat to apply (provider/model change is not live).")
                        // Результирующий LLM-конфиг для подтверждения.
                        AppTerminal.println("  provider: ${updated.provider.ifBlank { "(auto)" }}")
                        AppTerminal.println("  model:    ${updated.model}")
                        AppTerminal.println("  baseUrl:  ${updated.baseUrl}")
                    } catch (e: IllegalArgumentException) {
                        AppTerminal.err(e.message ?: "Unknown field")
                        AppTerminal.println("Supported fields: provider, model, baseUrl, apiKey")
                    }
                }
            }
            null, "" -> {
                AppTerminal.println("Usage: /config init | show | path | set <field> <value>")
            }
            else -> AppTerminal.println("Unknown /config command: $sub. Use: init, show, path, set")
        }
    }

    /** Description инструмента для таблицы: первая строка, обрезанная до ~80 символов. */
    private fun truncateDesc(description: String?): String {
        if (description.isNullOrBlank()) return ""
        val firstLine = description.lineSequence().firstOrNull().orEmpty().trim()
        return if (firstLine.length <= 80) firstLine else firstLine.take(80) + "…"
    }

    // ── /task: состояние задачи как конечный автомат (день 13) ──

    /**
     * День 15 (п.3): показать/сменить режим взаимодействия (MANUAL/PLAN/AUTO).
     * Режим хранится в [WorkingMemory.interactionMode] (per-chat, персистится). Default PLAN.
     */
    private suspend fun handleMode(input: String, agent: ContextAwareAgent) {
        val parts = input.trim().split("\\s+".toRegex())
        val w = agent.getWorkingMemory() ?: WorkingMemory()
        when {
            parts.size == 1 || parts[1] == "show" -> {
                AppTerminal.println("Interaction mode: ${w.interactionMode.name.lowercase()}")
                AppTerminal.println("  manual — свободный чат; FSM только через /task")
                AppTerminal.println("  plan   — stage-поток с подтверждением переходов (default)")
                AppTerminal.println("  auto   — полная автоматизация; переходы без подтверждения")
            }
            parts[1] in listOf("manual", "plan", "auto") -> {
                val newMode = InteractionMode.valueOf(parts[1].uppercase())
                agent.setWorkingMemory(w.copy(interactionMode = newMode))
                AppTerminal.ok("Interaction mode set to: ${newMode.name.lowercase()}")
            }
            else -> {
                AppTerminal.println("Usage: /mode [manual|plan|auto]")
                AppTerminal.println("Unknown mode: ${parts[1]}. Use: manual, plan, auto")
            }
        }
    }

    private suspend fun handleTask(input: String, agent: ContextAwareAgent, orchestrator: TaskOrchestrator) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2 || parts[1] == "show") {
            val ts = agent.getTaskState()
            if (ts == null) {
                AppTerminal.println("📋 No active task. Use: /task start <description>")
            } else {
                AppTerminal.println("📋 Task state:")
                AppTerminal.println("  Stage: ${ts.stage.name.lowercase()}")
                if (ts.awaitingAdvance) {
                    AppTerminal.println("  ⏳ Awaiting confirmation to advance (answer «да» or type feedback)")
                }
                ts.currentStep?.let { AppTerminal.println("  Current step: $it") }
                ts.requirements?.let { AppTerminal.println("  Requirements: $it") }
                ts.expectedAction?.let { AppTerminal.println("  Expected action: $it") }
                ts.approvedPlan?.let { AppTerminal.println("  Approved plan: $it") }
                ts.implementation?.let { AppTerminal.println("  Implementation: $it") }
                ts.verdict?.let { AppTerminal.println("  Verdict: $it") }
                if (ts.stageHistory.isNotEmpty()) {
                    AppTerminal.println("  History:")
                    ts.stageHistory.forEach {
                        AppTerminal.println("    - ${it.from.name.lowercase()}→${it.to.name.lowercase()}${it.note?.let { n -> " ($n)" } ?: ""}")
                    }
                }
            }
            return
        }

        when (parts[1]) {
            "start" -> {
                val desc = parts.drop(2).joinToString(" ").trim()
                if (desc.isEmpty()) {
                    AppTerminal.println("Usage: /task start <description>")
                    return
                }
                // День 13 (авто-поток): оркестратор сам выбирает стартовую стадию
                // (CLARIFY/PLANNING через EntryStageClassifier) и генерирует артефакт через LLM.
                // Запасной ручной путь — /task set <stage>.
                val w = agent.getWorkingMemory() ?: WorkingMemory()
                agent.setWorkingMemory(w.copy(currentTask = desc))
                // День 15 (progressive): блоки стадий печатаются по мере готовности через onEmit.
                currentSpinnerStage = null
                orchestrator.startTask(
                    desc, w.interactionMode,
                    onEmit = { block ->
                        AppTerminal.println()
                        AppTerminal.markdown(block)
                        AppTerminal.println()
                    },
                    onStageStart = { stage -> currentSpinnerStage = stage }
                )
            }
            "next" -> {
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                // День 15: единая точка перехода через TransitionGuard (структурная + артефактная
                // проверки). next() всегда даёт легальный forward-canonical → Illegal невозможен.
                val nextStage = TaskStateMachine.next(cur.stage)
                if (nextStage == null) {
                    AppTerminal.warn("No next stage from ${cur.stage.name.lowercase()} (already done?).")
                    return
                }
                when (val outcome = agent.attemptTransition(nextStage)) {
                    is TransitionOutcome.Allowed ->
                        AppTerminal.ok("Advanced to stage: ${outcome.newState.stage.name.lowercase()}")
                    is TransitionOutcome.ArtifactMissing ->
                        AppTerminal.warn("Stage ${cur.stage.name.lowercase()} not ready: missing artifact. ${outcome.hint}")
                    is TransitionOutcome.Illegal ->
                        // невозможно: next() всегда даёт легальный forward; защита от будущих изменений
                        AppTerminal.warn("⛔ Blocked: illegal transition ${cur.stage.name.lowercase()}→${nextStage.name.lowercase()}.")
                    null -> AppTerminal.println("No active task. Use: /task start <description>")
                }
            }
            "set" -> {
                if (parts.size < 3) {
                    AppTerminal.println("Usage: /task set <clarify|planning|execution|validation|done> [--force]")
                    return
                }
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                // День 15 (п.1 доработки): жёсткий режим. --force (или -f) — осознанный escape.
                val stageArg = parts[2]
                val stage = try {
                    TaskStage.valueOf(stageArg.uppercase())
                } catch (e: IllegalArgumentException) {
                    AppTerminal.println("Unknown stage: $stageArg. Use: clarify, planning, execution, validation, done")
                    return
                }
                val force = parts.any { it.equals("--force", ignoreCase = true) || it == "-f" }
                when (val outcome = agent.attemptTransition(stage, force)) {
                    is TransitionOutcome.Allowed ->
                        AppTerminal.ok("Stage set to: ${outcome.newState.stage.name.lowercase()}" +
                            if (force) " (forced)" else "")
                    is TransitionOutcome.Illegal -> {
                        val targets = outcome.allowedTargets.joinToString(", ") { it.name.lowercase() }
                        AppTerminal.warn("⛔ Blocked: illegal transition ${outcome.from.name.lowercase()}→" +
                            "${outcome.to.name.lowercase()}. Allowed: $targets. Use --force to override.")
                    }
                    is TransitionOutcome.ArtifactMissing ->
                        AppTerminal.warn("⛔ Blocked: stage ${outcome.from.name.lowercase()} not ready " +
                            "(missing artifact). ${outcome.hint} Use --force to override.")
                    null -> AppTerminal.println("No active task. Use: /task start <description>")
                }
            }
            "step" -> {
                val text = parts.drop(2).joinToString(" ").trim()
                if (text.isEmpty()) {
                    AppTerminal.println("Usage: /task step <text>")
                    return
                }
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                agent.setTaskState(cur.copy(currentStep = text))
                AppTerminal.ok("Current step updated.")
            }
            "expect" -> {
                val text = parts.drop(2).joinToString(" ").trim()
                if (text.isEmpty()) {
                    AppTerminal.println("Usage: /task expect <text>")
                    return
                }
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                agent.setTaskState(cur.copy(expectedAction = text))
                AppTerminal.ok("Expected action updated.")
            }
            "plan" -> {
                val text = parts.drop(2).joinToString(" ").trim()
                if (text.isEmpty()) {
                    AppTerminal.println("Usage: /task plan <text>")
                    return
                }
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                agent.setTaskState(cur.copy(approvedPlan = text))
                AppTerminal.ok("Approved plan updated.")
            }
            "impl" -> {
                val text = parts.drop(2).joinToString(" ").trim()
                if (text.isEmpty()) {
                    AppTerminal.println("Usage: /task impl <text>")
                    return
                }
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                agent.setTaskState(cur.copy(implementation = text))
                AppTerminal.ok("Implementation updated.")
            }
            "verdict" -> {
                val text = parts.drop(2).joinToString(" ").trim()
                if (text.isEmpty()) {
                    AppTerminal.println("Usage: /task verdict <text>")
                    return
                }
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                agent.setTaskState(cur.copy(verdict = text))
                AppTerminal.ok("Verdict updated.")
            }
            "back" -> {
                if (agent.getTaskState() == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                val reverted = agent.revertTaskState()
                if (reverted == null) {
                    AppTerminal.warn("No stage history to revert.")
                } else {
                    AppTerminal.ok("Reverted to stage: ${reverted.stage.name.lowercase()}")
                }
            }
            "done" -> {
                val cur = agent.getTaskState()
                if (cur == null) {
                    AppTerminal.println("No active task. Use: /task start <description>")
                    return
                }
                // День 15: единая точка через TransitionGuard. Из VALIDATION — артефактный gate
                // (нужен verdict); из других стадий — Illegal (надо пройти VALIDATION); escape —
                // /task set done --force (явный, не здесь).
                when (val outcome = agent.attemptTransition(TaskStage.DONE)) {
                    is TransitionOutcome.Allowed ->
                        AppTerminal.ok("Task done. (stage: ${outcome.newState.stage.name.lowercase()})")
                    is TransitionOutcome.ArtifactMissing ->
                        AppTerminal.warn("⛔ Blocked: validation not ready (missing verdict). ${outcome.hint} " +
                            "Use /task set done --force to override.")
                    is TransitionOutcome.Illegal -> {
                        val targets = outcome.allowedTargets.joinToString(", ") { it.name.lowercase() }
                        AppTerminal.warn("⛔ Blocked: cannot finish from ${cur.stage.name.lowercase()}. " +
                            "Reach VALIDATION first. Allowed from here: $targets. " +
                            "Use /task set done --force to override.")
                    }
                    null -> AppTerminal.println("No active task. Use: /task start <description>")
                }
            }
            "reset" -> {
                agent.setTaskState(null)
                AppTerminal.ok("Task state cleared (working memory task/plan kept).")
            }
            else -> AppTerminal.println("Unknown /task command: ${parts[1]}. Use: show, start, next, set, step, expect, plan, impl, verdict, back, done, reset")
        }
    }
}
