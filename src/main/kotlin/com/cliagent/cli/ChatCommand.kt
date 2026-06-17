package com.cliagent.cli

import com.cliagent.agent.Agent
import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.ProfileExtractor
import com.cliagent.config.ConfigRepository
import com.cliagent.context.ContextManager
import com.cliagent.context.HistoryCompressor
import com.cliagent.context.strategy.BranchingStrategy
import com.cliagent.context.strategy.ContextStrategyType
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.context.strategy.StickyFactsStrategy
import com.cliagent.context.strategy.SummaryStrategy
import com.cliagent.llm.OpenAiCompatibleClient
import com.cliagent.llm.model.ReasoningStrategy
import com.cliagent.llm.pricing.Pricing
import com.cliagent.memory.JsonChatStore
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.MemoryStore
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader

class ChatCommand : CliktCommand(name = "chat", help = "Start interactive chat with LLM") {
    private val model by option("-m", "--model", help = "Model name").default("glm-5.1")
    private val temperature by option("-t", "--temperature", help = "Temperature (0.0-2.0)").double().default(0.7)
    private val strategy by option("-s", "--strategy", help = "Reasoning: direct, step_by_step, meta_prompt, expert_group").default("direct")
    private val chat by option("-c", "--chat", help = "Chat ID (or 'new')").default("default")
    private val compress by option("--compress", help = "Enable auto-compression of history").flag()
    private val keepRecent by option("--keep-recent", help = "Keep last N messages uncompressed").int().default(10)
    private val contextStrategy by option("--context", help = "Context strategy: sliding, facts, summary, branch").default("sliding")
    private val autoProfile by option("--auto-profile", help = "Auto-extract user profile every N turns via LLM").flag()

    override fun run() = runBlocking {
        val config = try {
            ConfigRepository().load()
        } catch (e: IllegalStateException) {
            echo("Error: ${e.message}", err = true)
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

        val compressLabel = if (compress) "ON" else "OFF"
        echo("CLI Agent v0.5 | Chat: $chatId | Model: $model | Context: ${contextManager.getStrategy().getName()} | Compress: $compressLabel")
        echo("Type /help for commands, /exit to quit\n")

        val reader = BufferedReader(InputStreamReader(System.`in`))

        while (true) {
            echo("> ", trailingNewline = false)
            val input = reader.readLine() ?: break
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
                input == "/reset" -> {
                    agent.reset()
                    echo("History, summary, facts, branches, working memory cleared.")
                }
                else -> {
                    val response = agent.chat(input)
                    echo("\n$response\n")
                }
            }
        }
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
                println("Unknown strategy '$type', using sliding window")
                SlidingWindowStrategy(keepRecent)
            }
        }
        return ContextManager(strategy)
    }

    private fun printHelp() {
        echo("""
            |CLI Agent v0.5 — Commands:
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
        """.trimMargin())
    }

    private suspend fun printHistory(agent: Agent) {
        val history = agent.getHistory()
        if (history.isEmpty()) {
            echo("No messages yet.")
            return
        }
        history.forEachIndexed { i, msg ->
            val preview = if (msg.content.length > 100) msg.content.take(100) + "..." else msg.content
            echo("[${i + 1}] ${msg.role}: $preview")
        }
    }

    private suspend fun printChats(memoryStore: MemoryStore) {
        val chats = memoryStore.listChats()
        if (chats.isEmpty()) {
            echo("No saved chats.")
            return
        }
        chats.forEach { chat ->
            val tags = mutableListOf<String>()
            if (chat.summary != null) tags.add("summarized")
            if (chat.facts.isNotEmpty()) tags.add("${chat.facts.size} facts")
            if (chat.branches.isNotEmpty()) tags.add("${chat.branches.size} branches")
            val tagStr = if (tags.isNotEmpty()) " [${tags.joinToString(", ")}]" else ""
            echo("${chat.id}  ${chat.title}  (${chat.messages.size} msgs, updated ${chat.updatedAt.take(19)})$tagStr")
        }
    }

    private fun printStats(agent: ContextAwareAgent, model: String) {
        val stats = agent.getTokenStats()
        val estimated = agent.getEstimatedHistoryTokens()
        if (stats != null) {
            echo("""
                |📊 Token Statistics (session)
                |  Requests:      ${stats.requestCount}
                |  Prompt:        ${stats.totalPromptTokens} tokens
                |  Completion:    ${stats.totalCompletionTokens} tokens
                |  Total:         ${stats.totalTokens} tokens
                |  Cached:        ${stats.totalCachedTokens} tokens
                |  Last request:  prompt=${stats.lastRequestTokens?.promptTokens ?: "?"} completion=${stats.lastRequestTokens?.completionTokens ?: "?"} total=${stats.lastRequestTokens?.totalTokens ?: "?"}
                |  History est:   ~$estimated tokens (approx)
                |  Strategy:      ${agent.getCurrentStrategyName()}
            """.trimMargin())
        } else {
            echo("No token data yet. Send a message first.")
        }
    }

    private fun printCost(agent: ContextAwareAgent, model: String) {
        val stats = agent.getTokenStats()
        if (stats == null) {
            echo("No token data yet. Send a message first.")
            return
        }
        val price = Pricing.getPrice(model)
        if (price != null) {
            val inputCost = (stats.totalPromptTokens / 1_000_000.0) * price.input
            val outputCost = (stats.totalCompletionTokens / 1_000_000.0) * price.output
            val totalCost = inputCost + outputCost
            echo("""
                |💰 Estimated Cost (session)
                |  Model:         $model
                |  Input:         ${stats.totalPromptTokens} tokens × ${'$'}${String.format("%.2f", price.input)}/1M = ${'$'}${String.format("%.6f", inputCost)}
                |  Output:        ${stats.totalCompletionTokens} tokens × ${'$'}${String.format("%.2f", price.output)}/1M = ${'$'}${String.format("%.6f", outputCost)}
                |  Total:         ${'$'}${String.format("%.6f", totalCost)}
                |  Cached saved:  ${stats.totalCachedTokens} tokens
            """.trimMargin())
        } else {
            echo("No pricing data for model '$model'. Token counts: prompt=${stats.totalPromptTokens} completion=${stats.totalCompletionTokens} total=${stats.totalTokens}")
        }
    }

    private suspend fun printSummary(agent: ContextAwareAgent) {
        val summary = agent.getSummary()
        if (summary != null) {
            echo("📝 Conversation Summary:\n$summary")
        } else {
            echo("No summary yet. Use --compress flag or /compress command.")
        }
    }

    private suspend fun manualCompress(agent: ContextAwareAgent) {
        val summary = agent.compressNow()
        if (summary != null) {
            echo("✓ History compressed. Summary saved.")
        } else {
            echo("Compression not available. Start with --compress flag.")
        }
    }

    private fun printFacts(agent: ContextAwareAgent) {
        val cm = agent.getContextManager()
        val strategy = cm?.getStrategy() as? StickyFactsStrategy
        if (strategy != null) {
            val facts = strategy.getFacts()
            if (facts.isEmpty()) {
                echo("No facts extracted yet.")
            } else {
                echo("📋 Key Facts:")
                facts.forEach { (key, value) ->
                    echo("  $key: $value")
                }
            }
        } else {
            echo("Facts are only available with 'facts' strategy. Use /strategy facts or --context facts")
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
            echo("Current strategy: ${agent.getCurrentStrategyName()}")
            echo("Available: sliding, facts, summary, branch")
            return
        }
        val name = parts[1].lowercase()
        val newManager = createStrategy(name, client, model, memoryStore, chatId, keepRecent)
        val msg = agent.switchStrategy(newManager)
        echo(msg)
    }

    private suspend fun handleBranch(input: String, agent: ContextAwareAgent) {
        val cm = agent.getContextManager()
        val strategy = cm?.getStrategy() as? BranchingStrategy
        if (strategy == null) {
            echo("Branching requires 'branch' strategy. Use /strategy branch or --context branch")
            return
        }

        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            echo("Usage: /branch create <name> [at <N>] | /branch list | /branch switch <name>")
            return
        }

        when (parts[1]) {
            "create" -> {
                if (parts.size < 3) {
                    echo("Usage: /branch create <name> [at <N>]")
                    return
                }
                val name = parts[2]
                val fromIndex = if (parts.size >= 5 && parts[3] == "at") {
                    parts[4].toIntOrNull() ?: (agent.getHistory().size - 1)
                } else {
                    agent.getHistory().size - 1
                }
                val branchId = strategy.createBranch(name, fromIndex)
                echo("✓ Created branch '$name' from message #${fromIndex + 1} (id: $branchId)")
            }
            "list" -> {
                val branches = strategy.listBranches()
                echo("Branches:")
                branches.forEach { echo("  $it") }
            }
            "switch" -> {
                if (parts.size < 3) {
                    echo("Usage: /branch switch <name>")
                    return
                }
                val name = parts[2]
                // Find branch by name
                val result = strategy.switchBranch(name)
                if (result.isSuccess) {
                    echo("✓ Switched to branch '${result.getOrDefault("")}'")
                } else {
                    echo("Branch '$name' not found")
                }
            }
            else -> echo("Unknown branch command: ${parts[1]}. Use: create, list, switch")
        }
    }

    private suspend fun handleMemory(input: String, agent: ContextAwareAgent) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            // Сводка по всем слоям
            val history = agent.getHistory()
            val working = agent.getWorkingMemory()
            val longTerm = agent.getLongTermMemory()
            echo("""
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
                    echo("Usage: /memory show <short|working|long>")
                    return
                }
                when (parts[2]) {
                    "short" -> printHistory(agent)
                    "working" -> {
                        val w = agent.getWorkingMemory()
                        if (w == null || w.isEmpty()) {
                            echo("Working memory is empty.")
                        } else {
                            echo("🔧 Working memory:")
                            w.currentTask?.let { echo("  Task: $it") }
                            w.plan?.let { echo("  Plan: $it") }
                            w.scratchNotes?.let { echo("  Notes: $it") }
                            if (w.taskDecisions.isNotEmpty()) {
                                echo("  Decisions:")
                                w.taskDecisions.forEach { echo("    - $it") }
                            }
                        }
                    }
                    "long" -> {
                        val lt = agent.getLongTermMemory()
                        echo("💾 Long-term memory:")
                        if (lt.knowledge.isNotEmpty()) {
                            echo("  Knowledge:")
                            lt.knowledge.forEach { (k, v) -> echo("    - $k: $v") }
                        }
                        if (lt.decisions.isNotEmpty()) {
                            echo("  Decisions:")
                            lt.decisions.forEach { (k, v) -> echo("    - $k: $v") }
                        }
                        val p = lt.profile
                        if (p != null) {
                            echo("  Profile:")
                            p.style?.let { echo("    Style: $it") }
                            p.format?.let { echo("    Format: $it") }
                            if (p.constraints.isNotEmpty()) p.constraints.forEach { echo("    Constraint: $it") }
                        }
                        if (lt.isEmpty()) echo("  (empty)")
                    }
                    else -> echo("Unknown layer: ${parts[2]}. Use: short, working, long")
                }
            }
            "save" -> handleMemorySave(parts, agent)
            "clear" -> {
                if (parts.size < 3) {
                    echo("Usage: /memory clear <working|long>")
                    return
                }
                when (parts[2]) {
                    "working" -> {
                        agent.setWorkingMemory(WorkingMemory())
                        echo("✓ Working memory cleared for this chat.")
                    }
                    "long" -> {
                        echo("⚠️  Clearing ALL long-term memory (global, affects every chat).")
                        agent.setLongTermMemory(LongTermMemory())
                        echo("✓ Long-term memory cleared.")
                    }
                    else -> echo("Unknown layer: ${parts[2]}. Use: working, long")
                }
            }
            else -> echo("Unknown /memory command: ${parts[1]}. Use: show, save, clear")
        }
    }

    private suspend fun handleMemorySave(parts: List<String>, agent: ContextAwareAgent) {
        // /memory save <working|long> <field> [args...]
        if (parts.size < 4) {
            echo("Usage: /memory save working <task|plan|note|decision> <text>")
            echo("       /memory save long <knowledge|decision> <key> <text>")
            return
        }
        when (parts[2]) {
            "working" -> {
                val field = parts[3]
                val text = parts.drop(4).joinToString(" ").trim()
                if (text.isEmpty() && field !in listOf("task", "plan")) {
                    echo("Text is required for '$field'.")
                    return
                }
                val w = agent.getWorkingMemory() ?: WorkingMemory()
                val updated = when (field) {
                    "task" -> w.copy(currentTask = text.ifEmpty { null })
                    "plan" -> w.copy(plan = text.ifEmpty { null })
                    "note" -> w.copy(scratchNotes = listOfNotNull(w.scratchNotes, text)
                        .filter { it.isNotBlank() }.joinToString("\n"))
                    "decision" -> w.copy(taskDecisions = w.taskDecisions + text)
                    else -> { echo("Unknown working field: $field. Use: task, plan, note, decision"); return }
                }
                agent.setWorkingMemory(updated)
                echo("✓ Working memory '$field' updated.")
            }
            "long" -> {
                val field = parts[3]              // knowledge | decision
                if (parts.size < 5) {
                    echo("Usage: /memory save long $field <key> <text>")
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
                    else -> { echo("Unknown long field: $field. Use: knowledge, decision"); return }
                }
                agent.setLongTermMemory(updated)
                echo("✓ Long-term '$field' ${if (text.isEmpty()) "removed '$key'" else "updated '$key'"}.")
            }
            else -> echo("Unknown layer: ${parts[2]}. Use: working, long")
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
                echo("👤 User profile is empty. Use: /profile set style|format|about <text>, /profile add constraint <text>")
            } else {
                echo("👤 User profile:")
                p.style?.let { echo("  Style: $it") }
                p.format?.let { echo("  Format: $it") }
                p.about?.let { echo("  About: $it") }
                if (p.constraints.isNotEmpty()) {
                    echo("  Constraints:")
                    p.constraints.forEach { echo("    - $it") }
                }
            }
            return
        }

        when (parts[1]) {
            "set" -> {
                if (parts.size < 4) {
                    echo("Usage: /profile set <style|format|about> <text>")
                    return
                }
                val field = parts[2]
                val text = parts.drop(3).joinToString(" ").trim()
                if (text.isEmpty()) {
                    echo("Text is required.")
                    return
                }
                val cur = agent.getProfile() ?: UserProfile()
                val updated = when (field) {
                    "style" -> cur.copy(style = text)
                    "format" -> cur.copy(format = text)
                    "about" -> cur.copy(about = text)
                    else -> { echo("Unknown field: $field. Use: style, format, about"); return }
                }
                agent.setProfile(updated)
                echo("✓ Profile '$field' updated.")
            }
            "add" -> {
                if (parts.size < 4 || parts[2] != "constraint") {
                    echo("Usage: /profile add constraint <text>")
                    return
                }
                val text = parts.drop(3).joinToString(" ").trim()
                val cur = agent.getProfile() ?: UserProfile()
                agent.setProfile(cur.copy(constraints = cur.constraints + text))
                echo("✓ Constraint added.")
            }
            "remove" -> {
                if (parts.size < 4 || parts[2] != "constraint") {
                    echo("Usage: /profile remove constraint <text>")
                    return
                }
                val text = parts.drop(3).joinToString(" ").trim()
                val cur = agent.getProfile() ?: UserProfile()
                val filtered = cur.constraints.filterNot { it == text || it.contains(text) }
                if (filtered.size == cur.constraints.size) {
                    echo("No matching constraint found.")
                } else {
                    agent.setProfile(cur.copy(constraints = filtered))
                    echo("✓ Constraint removed.")
                }
            }
            "extract" -> {
                val history = agent.getHistory()
                if (history.isEmpty()) {
                    echo("No dialog yet to infer profile from.")
                    return
                }
                echo("🔄 Inferring profile from dialog...")
                val extractor = ProfileExtractor(client, model)
                val cur = agent.getProfile()
                val merged = extractor.extract(history, cur)
                agent.setProfile(merged)
                echo("✓ Profile inferred and merged. Use /profile to view.")
            }
            "clear" -> {
                agent.setProfile(null)
                echo("✓ Profile cleared.")
            }
            else -> echo("Unknown /profile command: ${parts[1]}. Use: show, set, add, remove, extract, clear")
        }
    }
}
