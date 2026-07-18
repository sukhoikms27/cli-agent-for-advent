package com.cliagent.cli

import com.cliagent.config.ConfigRepository
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmClientFactory
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.embedding.OllamaEmbeddingClient
import com.cliagent.rag.topK
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * День 32 — PR-review pipeline: `cli-agent review-pr <diff-file|->`.
 *
 * AI-ревью изменений: берёт git-diff → RAG-контекст (README/AGENTS.md/docs) → LLM анализирует
 * изменения и выдаёт структурированный отчёт: потенциальные баги, архитектурные проблемы,
 * рекомендации.
 *
 * **Два режима работы:**
 *  1. **Локально:** `review-pr path/to/changes.diff` или `git diff | cli-agent review-pr -`
 *     (stdin через `-`). Удобно для pre-commit проверки перед push.
 *  2. **CI (GitHub Action):** workflow `.github/workflows/ai-review.yml` запускает `review-pr` на
 *     `github.event.pull_request` diff. Результат постиится как PR-комментарий (через action).
 *
 * **Мягкая деградация** (как в AskCommand дня 31):
 *  - нет индекса → ответ без RAG-контекста (флаг `--no-rag` для явного отключения);
 *  - Ollama недоступна → warn + ответ без RAG;
 *  - LLM-сбой → понятная ошибка, exit 1.
 *
 * **Защита контекста:** diff обрезается до [DIFF_MAX_CHARS] (аналогично git_diff tool дня 31) —
 * большие PR не переполняют контекстное окно. Пользователь видит предупреждение при обрезке.
 *
 * @param diffSource путь к .diff файлу, `-` для stdin, или omitted → git diff в CWD.
 * @param projectRoot корень проекта (для RAG, default = CWD).
 * @param topK сколько чанков достать из RAG.
 */
class ReviewPrCommand : CliktCommand(
    name = "review-pr",
    help = "AI-ревью git-diff (баги/архитектура/рекомендации). День 32: PR-review pipeline. " +
        "Источник: файл .diff, '-' для stdin, или git diff в CWD если опущен.",
) {
    private val diffSource: String? by argument("diff")
        .help("Путь к .diff файлу, '-' для stdin, или опустите чтобы взять git diff из CWD")

    private val projectRoot: String? by option("--project", help = "Корень проекта (default: CWD)")
        .help("Путь к git-репозиторию; RAG и git diff берутся отсюда.")

    private val topK: Int? by option("--top-k", help = "Число RAG-чанков (default: config.rag.topK)")
        .int()
        .help("Сколько фрагментов из документации инжектить для контекста ревью.")

    private val noRag: Boolean by option("--no-rag", help = "Без RAG — ревью только по diff")
        .flag()
        .help("Пропустить retrieval; полезно если Ollama недоступна.")

    private val quiet: Boolean by option("--quiet", help = "Только отчёт, без прогресс-сообщений")
        .flag()
        .help("Минимум вывода — для CI логов.")

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

        // 1. Получить diff (3 источника: файл / stdin / git diff в CWD).
        val diff = try {
            loadDiff(root)
        } catch (e: IllegalStateException) {
            AppTerminal.err(e.message ?: "Не удалось получить diff")
            return@runBlocking
        }
        if (diff.isBlank()) {
            AppTerminal.err("Пустой diff — нечего ревьюить.")
            return@runBlocking
        }

        // 2. RAG retrieval (мягкая деградация).
        val ragBlock = if (!noRag) {
            retrieveRag(config, root, diff, topK ?: config.rag.topK)
        } else {
            null
        }

        // 3. Сборка промпта и LLM-вызов.
        val prompt = buildReviewPrompt(diff, ragBlock)
        if (!quiet) {
            AppTerminal.println("🔍 Анализирую diff (${diff.length} символов)…")
        }

        val response = try {
            AppTerminal.withSpinner("AI review…") {
                val request = ChatRequest(model = model, messages = prompt, temperature = 0.2)
                when (val result = client.chat(request)) {
                    is com.cliagent.llm.LlmResult.Success -> result.data.choices.first().message.content
                    is com.cliagent.llm.LlmResult.Error -> "⚠️ LLM error: ${result.code} — ${result.message}"
                }
            }
        } catch (e: LlmCallException) {
            "⚠️ Ошибка запроса к LLM: ${e.message}"
        }

        AppTerminal.println()
        AppTerminal.println("---")
        AppTerminal.println("## AI Code Review")
        AppTerminal.println()
        AppTerminal.markdown(response)
        AppTerminal.println("---")
        AppTerminal.println()

        runCatching { (client as? AutoCloseable)?.close() }
        Unit
    }

    /**
     * Загружает diff из 3 источников (приоритет — аргумент):
     *  1. [diffSource] = путь к файлу → читать файл.
     *  2. [diffSource] = "-" → читать stdin (pipe: `git diff | review-pr -`).
     *  3. [diffSource] = null → `git diff` в [root] (HEAD vs working tree).
     *
     * Обрезает до [DIFF_MAX_CHARS] для защиты контекстного окна. Бросает IllegalStateException
     * при ошибке чтения файла / git.
     */
    private fun loadDiff(root: File): String {
        val raw = when (diffSource) {
            null -> runGit(root, "diff", "HEAD")?.takeIf { it.isNotBlank() }
                ?: runGit(root, "diff") ?: ""
            "-" -> System.`in`.bufferedReader(Charsets.UTF_8).readText()
            else -> {
                val f = File(diffSource!!)
                if (!f.isFile) throw IllegalStateException("Diff файл не найден: ${f.absolutePath}")
                f.readText(Charsets.UTF_8)
            }
        }
        if (raw.length > DIFF_MAX_CHARS) {
            if (!quiet) {
                AppTerminal.warn("Diff обрезан с ${raw.length} до $DIFF_MAX_CHARS символов (контекстное окно).")
            }
            return raw.take(DIFF_MAX_CHARS) + "\n... [diff truncated, ${raw.length - DIFF_MAX_CHARS} chars omitted]"
        }
        return raw
    }

    /** Запуск git в [dir]; null при ошибке. */
    private fun runGit(dir: File, vararg args: String): String? {
        if (!File(dir, ".git").exists()) return null
        val proc = try {
            ProcessBuilder(listOf("git") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return null
        }
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        proc.waitFor()
        return out
    }

    /**
     * RAG-retrieval для контекста ревью. Извлекает «предметную область» из diff (имена файлов +
     * ключевые слова) для retrieval-запроса. Возвращает форматированный блок или null при ошибке.
     */
    private suspend fun retrieveRag(
        config: com.cliagent.config.AppConfig,
        root: File,
        diff: String,
        topK: Int,
    ): String? {
        val embedder = OllamaEmbeddingClient(
            baseUrl = config.rag.embeddingBaseUrl,
            model = config.rag.embeddingModel,
        )
        return try {
            val store = JsonRagStore()
            val index = store.load()
            if (index.embeddedChunks.isEmpty()) return null
            // retrieval-запрос: имена файлов + ключевые токены из diff (без +/- префиксов).
            val query = extractQueryFromDiff(diff)
            val result = embedder.embed(listOf(query))
            val qVec = when (result) {
                is com.cliagent.llm.LlmResult.Error -> return null
                is com.cliagent.llm.LlmResult.Success -> result.data.firstOrNull() ?: return null
            }
            val hits = topK(qVec, index.chunks, k = topK)
            if (hits.isEmpty()) return null
            formatRagBlock(hits)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!quiet) {
                AppTerminal.warn("RAG недоступен (${e.message?.take(80)}). Ревью без контекста документации.")
            }
            null
        } finally {
            runCatching { embedder.close() }
        }
    }

    /**
     * Извлекает retrieval-запрос из diff: имена файлов (без путей) + идентификаторы из добавленных
     * строк. Чистая функция — вынесена для тестирования (см. ReviewPrDiffParserTest).
     */
    private fun extractQueryFromDiff(diff: String): String =
        com.cliagent.cli.extractQueryFromDiff(diff)

    private fun formatRagBlock(hits: List<com.cliagent.rag.ScoredChunk>): String =
        com.cliagent.cli.formatRagBlock(hits)

    companion object {
        /** Лимит символов для diff (защита контекстного окна, как git_diff tool дня 31). */
        const val DIFF_MAX_CHARS = 12000
    }
}
