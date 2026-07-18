package com.cliagent.cli

import com.cliagent.config.ConfigRepository
import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmClientFactory
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagRetriever
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
 * День 31 — Dev-assistant: `cli-agent ask <question>`.
 *
 * Ассистент разработчика, отвечающий на вопросы о проекте. Источники знаний:
 *  1. **RAG** — индекс над README/docs/AGENTS.md (создаётся заранее через `/rag index` в REPL или
 *     автоиндексируется через `--reindex`, если индекс пуст/отсутствует).
 *  2. **Git context** — текущая ветка и short status, инжектируются в промпт (без MCP-зависимостей
 *     в CLI: git вызывается напрямую через [ProcessBuilder], read-only). Даёт модели контекст
 *     «над чем сейчас идёт работа».
 *
 * Использует [SystemPrompts.devAssistant] — жёсткие правила отвечать только из контекста, называть
 * источник, «не знаю» при недостатке данных.
 *
 * **Мягкая деградация** (как в основном RAG-пути):
 *  - нет индекса → авто-индексация через `--reindex` ИЛИ ответ без RAG-блока (флаг `--no-rag`);
 *  - Ollama недоступна (эмбеддинги) → ответ без RAG, только git-контекст;
 *  - LLM-сбой → понятная ошибка, exit 1.
 *
 * **Не REPL:** одноразовый вопрос-ответ, без истории диалога. Для интерактивной сессии — `chat`.
 *
 * @param projectRoot корень проекта (default = CWD); git-тулы и RAG ищут индекс в данных проекта.
 * @param topK сколько чанков достать из RAG (default = config.rag.topK)
 */
class AskCommand : CliktCommand(
    name = "ask",
    help = "Спросить ассистента о проекте (RAG над README/docs + git-контекст). День 31: dev-assistant.",
) {
    private val question: String by argument("question")
        .help("Вопрос о проекте: структуре, зависимостях, текущей ветке, и т.д.")

    private val projectRoot: String? by option("--project", help = "Корень проекта (default: CWD)")
        .help("Путь к git-репозиторию; git-контекст (branch/status) берётся отсюда.")

    private val topK: Int? by option("--top-k", help = "Число RAG-чанков (default: config.rag.topK)")
        .int()
        .help("Сколько фрагментов из индекса инжектить в промпт.")

    private val noRag: Boolean by option("--no-rag", help = "Без RAG — только git-контекст + модель")
        .flag()
        .help("Пропустить retrieval; полезно, если Ollama недоступна или вопрос вне документации.")

    private val reindex: Boolean by option("--reindex", help = "Переиндексировать README/docs перед ответом")
        .flag()
        .help("Запустить /rag index эквивалент, если индекс пуст или нужен свежий.")

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

        // 1. Git-контекст (read-only): текущая ветка + короткий status.
        val gitContext = buildGitContext(root)

        // 2. RAG retrieval (мягкая деградация при ошибке).
        val ragBlock = if (!noRag) {
            retrieveRag(config, root, topK ?: config.rag.topK)
        } else {
            null
        }

        // 3. Сборка промпта: devAssistant system + git context + RAG block + вопрос.
        val prompt = buildPrompt(question, gitContext, ragBlock)

        AppTerminal.println()   // визуальный отступ перед ответом
        val response = try {
            AppTerminal.withSpinner("Dev-assistant думает…") {
                val request = ChatRequest(model = model, messages = prompt)
                when (val result = client.chat(request)) {
                    is com.cliagent.llm.LlmResult.Success -> result.data.choices.first().message.content
                    is com.cliagent.llm.LlmResult.Error -> "⚠️ LLM error: ${result.code} — ${result.message}"
                }
            }
        } catch (e: LlmCallException) {
            "⚠️ Ошибка запроса к LLM: ${e.message}"
        }
        AppTerminal.println()
        AppTerminal.markdown(response)
        AppTerminal.println()

        runCatching { (client as? AutoCloseable)?.close() }
        Unit
    }

    /**
     * Read-only git-контекст: текущая ветка + short status. Запускает `git branch --show-current`
     * и `git status --porcelain` в [root]. Не падает, если [root] — не репозиторий (возвращает
     * строку-заглушку). CancellationException пробрасывается.
     */
    private fun buildGitContext(root: File): String {
        if (!File(root, ".git").exists()) {
            return "(не git-репозиторий: ${root.absolutePath})"
        }
        val branch = runGit(root, listOf("branch", "--show-current")).trim()
        val status = runGit(root, listOf("status", "--porcelain")).trim()
        val statusSummary = if (status.isEmpty()) {
            "no uncommitted changes"
        } else {
            val lines = status.lines()
            "${lines.size} changed: " + lines.joinToString(", ") { it.trim() }.take(300)
        }
        return "branch: ${branch.ifBlank { "(detached HEAD)" }} | $statusSummary"
    }

    /** Запуск `git` в [dir]; возвращает stdout (пустая строка при ошибке). */
    private fun runGit(dir: File, args: List<String>): String {
        val proc = try {
            ProcessBuilder(listOf("git") + args)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return ""
        }
        val out = proc.inputStream.bufferedReader(Charsets.UTF_8).readText().trim()
        proc.waitFor()
        return out
    }

    /**
     * RAG-retrieval: создаёт embedder (Ollama), достаёт top-K чанков для вопроса. Возвращает
     * форматированный блок `[Retrieved context]` или null при ошибке (Ollama недоступна / пустой
     * индекс / флаг [noRag]). Embedder закрывается в finally.
     */
    private suspend fun retrieveRag(
        config: com.cliagent.config.AppConfig,
        root: File,
        topK: Int,
    ): String? {
        val embedder = OllamaEmbeddingClient(
            baseUrl = config.rag.embeddingBaseUrl,
            model = config.rag.embeddingModel,
        )
        return try {
            val store = JsonRagStore()
            var index = store.load()
            // --reindex или пустой индекс → переиндексация через существующий pipeline.
            if (reindex || index.embeddedChunks.isEmpty()) {
                AppTerminal.println("📚 Индекс пуст/устарел — индексирую проект (это потребует Ollama)…")
                index = reindexProject(config, root, store) ?: return null
            }
            if (index.embeddedChunks.isEmpty()) {
                AppTerminal.warn("RAG-индекс пуст после индексации. Ответ без контекста документации.")
                return null
            }
            // Прямой topK без full RagRetriever (stateless one-shot, без rewriter/reranker).
            val result = embedder.embed(listOf(question))
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
            AppTerminal.warn("RAG недоступен (${e.message?.take(100)}). Ответ без контекста документации.")
            null
        } finally {
            runCatching { embedder.close() }
        }
    }

    /** Переиндексация через DocumentLoader + RagIndexer (mirror of RagCommands.handleIndex). */
    private suspend fun reindexProject(
        config: com.cliagent.config.AppConfig,
        root: File,
        store: JsonRagStore,
    ): com.cliagent.rag.RagIndex? {
        val embedder = OllamaEmbeddingClient(
            baseUrl = config.rag.embeddingBaseUrl,
            model = config.rag.embeddingModel,
        )
        val docsRoots = config.rag.corpusRoots.map { File(root, it).absolutePath }
        val docs = com.cliagent.rag.DocumentLoader(docsRoots).load()
        if (docs.isEmpty()) return null
        val chunker = com.cliagent.rag.chunk.StructuralChunker(
            config.rag.chunkSizeTokens, config.rag.chunkOverlapTokens,
        )
        val indexer = com.cliagent.rag.RagIndexer(chunker, embedder, store)
        return indexer.index(docs) { done, total ->
            AppTerminal.println("  indexed $done/$total chunks")
        }
    }

    /** Форматирует чанки в блок [Retrieved context] для инъекции в промпт (делегирует в чистую [formatRagBlock]). */
    private fun formatRagBlock(hits: List<com.cliagent.rag.ScoredChunk>): String =
        com.cliagent.cli.formatRagBlock(hits)

    /**
     * Сборка цепочки сообщений: devAssistant system + git/RAG context + user question.
     * Контекст вшивается в system-prompt (а не отдельным user-сообщением) — так модель надёжнее
     * опирается на него как на «факты о проекте», а не «часть диалога». Делегирует в чистую
     * [buildAskPrompt] (тестируется без IO).
     */
    private fun buildPrompt(question: String, gitContext: String, ragBlock: String?): List<ChatMessage> =
        buildAskPrompt(question, gitContext, ragBlock, SystemPrompts.devAssistant.content)
}
