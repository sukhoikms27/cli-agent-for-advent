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
import com.cliagent.llm.OpenAiCompatibleClient
import com.cliagent.llm.model.ReasoningStrategy
import com.cliagent.llm.pricing.Pricing
import com.cliagent.memory.JsonChatStore
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import com.cliagent.state.TaskStateMachine
import com.cliagent.state.TransitionOutcome
import com.cliagent.state.InteractionMode
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantCategory
import com.cliagent.state.invariant.LlmInvariantChecker
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.runBlocking

class ChatCommand : CliktCommand(name = "chat", help = "Start interactive chat with LLM") {
    private val model by option("-m", "--model", help = "Model name").default("glm-5.1")
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

        val client = OpenAiCompatibleClient(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey
        )

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

        val reasoningStrategy = ReasoningStrategy.entries.find { it.label == strategy }
        val historyCompressor = if (compress) {
            HistoryCompressor(client, model, keepRecentCount = keepRecent)
        } else {
            null
        }

        // Create context strategy
        val contextManager = createStrategy(contextStrategy, client, model, memoryStore, chatId, keepRecent)

        // День 12: авто-извлечение профиля (opt-in)
        val profileExtractor = if (autoProfile) ProfileExtractor(client, model) else null

        val agent = ContextAwareAgent(
            llmClient = client,
            memoryStore = memoryStore,
            model = model,
            chatId = chatId,
            reasoningStrategy = reasoningStrategy,
            historyCompressor = historyCompressor,
            contextManager = contextManager,
            profileExtractor = profileExtractor,
            autoProfileEvery = if (autoProfile) 5 else 0
        )

        // День 13 (авто-поток стадий): оркестратор автоматизирует /task start → артефакт стадии →
        // подтверждение перехода. Один StageAgent на каждую стадию FSM + StepAgent на каждый
        // пункт плана внутри execution. Свободный текст при активной задаче = подтверждение/уточнение.
        // День 15 (B, gap №6): оркестратор использует защищённый chat-провайдер от StatefulAgent →
        // инварианты (если включены) покрывают и stage-поток, а не только свободный чат.
        val checker = if (invariantsEnabled) LlmInvariantChecker(client, model) else null
        val statefulAgent = StatefulAgent(agent, checker) { agent.getInvariants() }
        // День 15 (progressive): спиннер на уровне одного stage-LLM-вызова. Каждый chat()-вызоз
        // оркестратора крутит спиннер с лейблом текущей стадии (spinnerLabel читает
        // currentSpinnerStage, который ставится через onStageStart); finally чистит кадр до
        // печати блока → гарблинга нет. Блоки стадий печатаются progressive через onEmit.
        val orchestrator = TaskOrchestrator(
            agent, client, model,
            chat = { msg -> AppTerminal.withSpinner({ spinnerLabel() }) { statefulAgent.chat(msg) } }
        )

        // День 15 (п.1): авто-определение «простой вопрос vs задача» при отсутствии активной задачи
        // и режиме ≠ MANUAL. QUESTION → обычный чат; TASK → автостарт FSM без /task start.
        val intentClassifier = IntentClassifier(client, model)

        val invariantsLabel = if (invariantsEnabled) "ON" else "OFF"
        val compressLabel = if (compress) "ON" else "OFF"
        val modeLabel = (agent.getWorkingMemory()?.interactionMode ?: InteractionMode.PLAN).name.lowercase()
        AppTerminal.println("CLI Agent v0.8 | Chat: $chatId | Model: $model | Context: ${contextManager.getStrategy().getName()} | Compress: $compressLabel | Invariants: $invariantsLabel | Mode: $modeLabel")
        AppTerminal.println("Type /help for commands, /exit to quit")

        val repl = ReplEngine()

        while (true) {
            val input = repl.readLine() ?: break   // null = Ctrl+D
            if (input.isBlank()) continue

            when {
                input == "/exit" -> break
                input == "/help" -> printHelp()
                input == "/history" -> printHistory(agent)
                input == "/chats" -> printChats(memoryStore)
                input == "/stats" -> printStats(agent, model)
                input == "/cost" -> printCost(agent, model)
                input == "/summary" -> printSummary(agent)
                input == "/compress" -> manualCompress(agent)
                input == "/facts" -> printFacts(agent)
                input.startsWith("/strategy") -> handleStrategy(input, agent, client, model, memoryStore, chatId, keepRecent)
                input.startsWith("/branch") -> handleBranch(input, agent)
                input.startsWith("/memory") -> handleMemory(input, agent)
                input.startsWith("/profile") -> handleProfile(input, agent, client, model)
                input.startsWith("/invariants") -> handleInvariants(input, agent)
                input.startsWith("/task") -> handleTask(input, agent, orchestrator)
                input.startsWith("/mode") -> handleMode(input, agent)
                input == "/reset" -> {
                    agent.reset()
                    AppTerminal.ok("History, summary, facts, branches, working memory cleared.")
                }
                else -> {
                    // День 15 (progressive): каждый блок стадии печатается сразу через onEmit
                    // по мере готовности; onStageStart ставит лейбл спиннера per-stage.
                    dispatchFreeText(
                        input, agent, statefulAgent, orchestrator, intentClassifier,
                        onEmit = { block ->
                            AppTerminal.println()
                            AppTerminal.markdown(block)
                            AppTerminal.println()
                        },
                        onStageStart = { stage -> currentSpinnerStage = stage }
                    )
                }
            }
        }
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
        val response = try {
            AppTerminal.withSpinner({ spinnerLabel() }) { statefulAgent.chat(input) }
        } catch (e: LlmCallException) {
            val msg = "⚠️ Ошибка запроса к LLM: ${e.message}"
            onEmit(msg)
            return msg
        }
        onEmit(response)
        return response
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
            |  -m, --model <name>       — Model name (default: glm-5.1)
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
