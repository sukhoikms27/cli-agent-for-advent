package com.cliagent.cli

import com.cliagent.agent.ContextAwareAgent
import com.cliagent.agent.FileToolExecutor
import com.cliagent.config.ConfigRepository
import com.cliagent.context.ContextManager
import com.cliagent.context.strategy.SlidingWindowStrategy
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmClientFactory
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.memory.JsonChatStore
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextColors.green
import com.github.ajalt.mordant.rendering.TextColors.yellow
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * День 34 — File agent: `cli-agent file-agent <goal>`.
 *
 * Агент, который САМ работает с файлами проекта для достижения цели. Пользователь задаёт ЦЕЛЬ
 * (не «открой файл X»), а агент через file-tools (read/find/list/write) исследует, анализирует
 * и предлагает изменения. По лекции нед.7 — это «мини-ОС» с Tool Registry + Dangerous Operations.
 *
 * **Поддерживаемые сценарии (минимум 2 по заданию):**
 *  1. «Найди все TODO/FIXME в проекте» — find_in_files + агрегация.
 *  2. «Обнови README на основе текущего AGENTS.md» — read + write (с подтверждением).
 *  3. «Проверь соответствие кода инвариантам AGENTS.md» — find + read + анализ.
 *
 * **Human-in-the-Loop (лекция нед.7):** `write_file` требует подтверждения через [confirmWrite].
 * В интерактивном режиме (TTY) — y/N prompt; в batch/CI (`--yes-to-all` небезопасен — намеренно
 * отсутствует, fail-safe). Read-only операции — без подтверждения.
 *
 * **Мягкая деградация** (как AskCommand/ReviewPrCommand):
 *  - LLM-сбой → понятная ошибка, exit 1;
 *  - agent loops (maxToolRounds=15) — больше чем chat (8), т.к. file-задачи многоступенчатые.
 *
 * @param goal задача на уровне цели («найди все TODO», «обнови README», и т.д.)
 * @param projectRoot sandbox для file-операций (default = CWD)
 */
class FileAgentCommand : CliktCommand(
    name = "file-agent",
    help = "File-агент: работа с файлами проекта по цели (read/find/write с подтверждением). " +
        "День 34: агент сам инициирует операции с файлами.",
) {
    private val goal: String by argument("goal")
        .help("Цель: «найди все TODO», «обнови README по AGENTS.md», «проверь инварианты»")

    private val projectRoot: String? by option("--project", help = "Sandbox-корень (default: CWD)")
        .help("Путь к проекту; file-операции ограничены этим каталогом.")

    private val dryRun: Boolean by option("--dry-run", help = "Только чтение — без write_file (безопасный режим)")
        .flag()
        .help("Отключает write_file (только анализ). Для preview без изменений.")

    override fun run() = runBlocking {
        val config = try {
            ConfigRepository().load()
        } catch (e: IllegalStateException) {
            AppTerminal.err("config error: ${e.message}")
            return@runBlocking
        }
        val client = LlmClientFactory.create(config)
        val model = config.model.ifBlank { "glm-5.1" }
        val root = (projectRoot ?: System.getProperty("user.dir")).let { File(it).absoluteFile }

        // confirmWrite: в --dry-run — null (read-only fail-safe). Иначе — y/N prompt.
        val confirmWrite: (suspend (String, String) -> Boolean)? = if (dryRun) null else ::confirmWritePrompt

        val toolExecutor = FileToolExecutor(root = root, confirmWrite = confirmWrite)
        val store = JsonChatStore()
        val chatId = store.createChat().id
        val contextManager = ContextManager(SlidingWindowStrategy(windowSize = 10))

        val agent = ContextAwareAgent(
            llmClient = client,
            memoryStore = store,
            model = model,
            chatId = chatId,
            systemPrompt = SystemPrompts.fileAgent,
            contextManager = contextManager,
            temperature = 0.3,   // низкая — file ops требуют точности
            toolExecutor = toolExecutor,
            maxToolRounds = 15,  // file-задачи многоступенчатые (read → find → read → write)
            logger = { msg -> AppTerminal.println(gray(msg)) },
        )

        AppTerminal.println("📂 File agent | sandbox: ${root.absolutePath} | dry-run: $dryRun")
        AppTerminal.println("🎯 Цель: $goal")
        AppTerminal.println()

        val response = try {
            AppTerminal.withSpinner("File agent работает…") {
                agent.chat(goal)
            }
        } catch (e: LlmCallException) {
            "⚠️ Ошибка запроса к LLM: ${e.message}"
        }

        AppTerminal.println()
        AppTerminal.println("---")
        AppTerminal.markdown(response)
        AppTerminal.println("---")

        runCatching { (client as? AutoCloseable)?.close() }
        Unit
    }

    /**
     * y/N prompt для подтверждения write-операции (Human-in-the-Loop, лекция нед.7).
     * Показывает путь + preview содержимого (первые 500 символов), спрашивает y/N.
     * Default = N (fail-safe: сомнительно → отказ).
     */
    private suspend fun confirmWritePrompt(path: String, content: String): Boolean {
        AppTerminal.println()
        AppTerminal.println(yellow("⚠️  Запись файла: $path"))
        AppTerminal.println(yellow("   Размер: ${content.length} символов"))
        // Preview — первые строки, чтобы пользователь видел что подтверждает.
        val preview = content.lineSequence().take(8).joinToString("\n")
        AppTerminal.println(gray("   Preview:\n$preview"))
        AppTerminal.print("   Разрешить запись? [y/N] ")

        val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        val answer = try {
            reader.readLine()?.trim()?.lowercase()
        } catch (e: Exception) {
            null
        }
        val approved = answer == "y" || answer == "yes"
        AppTerminal.println(if (approved) green("   ✓ Подтверждено") else "   ✗ Отклонено")
        return approved
    }
}
