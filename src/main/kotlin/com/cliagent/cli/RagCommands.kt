package com.cliagent.cli

import com.cliagent.config.AppPaths
import com.cliagent.rag.ChunkingComparison
import com.cliagent.rag.DocumentLoader
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagChunk
import com.cliagent.rag.RagConfig
import com.cliagent.rag.RagIndex
import com.cliagent.rag.RagIndexer
import com.cliagent.rag.ScoredChunk
import com.cliagent.rag.chunk.ChunkingStrategy
import com.cliagent.rag.chunk.ChunkingStrategyType
import com.cliagent.rag.chunk.FixedSizeChunker
import com.cliagent.rag.chunk.StructuralChunker
import com.cliagent.rag.embedding.EmbeddingClient
import com.cliagent.rag.embedding.OllamaEmbeddingClient
import com.cliagent.rag.topK
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * День 21–22: обработчик slash-команды `/rag`. Зеркалирует структуру `handleMcp` в [ChatCommand]:
 * разбор частей → dispatch по подкоманде.
 *
 * **Важно:** RAG опционален. Без установленной Ollama команды индексации/eval деградируют с
 * понятным сообщением; остальной агент не страдает.
 *
 * Команды:
 * - `/rag` — сводка (статус RAG-режима + Ollama + текущий индекс)
 * - `/rag index [fixed|structural]` — переиндексация корпуса выбранной стратегией
 * - `/rag stats` — статистика текущего индекса (mordant-таблица)
 * - `/rag compare` — построить оба индекса + сравнительная таблица + пробный retrieval
 * - `/rag search <query>` — пробный retrieval top-5 (без агента; smoke-test)
 * - `/rag on` / `/rag off` — toggle RAG-режима агента (инъекция в промпт, день 22)
 * - `/rag eval` — прогон 10 контрольных вопросов с/без RAG + сравнительный отчёт (день 22)
 * - `/rag config` — показать RagConfig
 *
 * @param agent агент для toggle on|off и eval-прогона
 * @param chat  chat-функция (statefulAgent.chat под спиннером) — для eval, чтобы RAG-инъекция
 *              шла через защищённый StatefulAgent (инварианты покрывают RAG-ответы)
 */
internal class RagCommands(
    private val config: RagConfig,
    private val sharedEmbedder: OllamaEmbeddingClient,
    private val agent: com.cliagent.agent.ContextAwareAgent,
    private val chat: suspend (String) -> String,
) {

    /** Ленивый embedder для index/compare — переиспользует shared-инстанс сессии. */
    private fun embedder(): OllamaEmbeddingClient = sharedEmbedder

    suspend fun handle(input: String) {
        val parts = input.trim().split("\\s+".toRegex())
        if (parts.size < 2) {
            printSummary()
            return
        }
        when (parts[1]) {
            "index" -> handleIndex(parts)
            "stats" -> handleStats(parts)
            "compare" -> handleCompare(parts)
            "search" -> handleSearch(parts)
            "on" -> { agent.setRagEnabled(true); AppTerminal.ok("RAG injection: ON") }
            "off" -> { agent.setRagEnabled(false); AppTerminal.ok("RAG injection: OFF") }
            "eval" -> handleEval(parts)
            "config" -> printConfig()
            else -> AppTerminal.println(
                "Unknown /rag command: ${parts[1]}. Use: index, stats, compare, search, on, off, eval, config"
            )
        }
    }

    // ── /rag — сводка ──────────────────────────────────────────────────────────

    /** Сводка: runtime-режим агента (день 22), модель эмбеддинга, текущий индекс. */
    private suspend fun printSummary() {
        val runtimeState = if (agent.isRagEnabled()) "ON" else "OFF"
        AppTerminal.println("📚 RAG injection: $runtimeState  (config default: ${if (config.enabled) "ON" else "OFF"})")
        AppTerminal.println("   Embeddings: ${config.embeddingProvider} / ${config.embeddingModel} (${config.embeddingBaseUrl})")
        AppTerminal.println("   Corpus: ${config.corpusRoots.joinToString(", ")}")
        AppTerminal.println("   Chunking: size=${config.chunkSizeTokens} overlap=${config.chunkOverlapTokens}, topK=${config.topK}")
        val idx = loadActiveIndex()
        if (idx.chunks.isEmpty()) {
            AppTerminal.println("   Index: empty. Use: /rag index")
        } else {
            AppTerminal.println("   Index: ${idx.chunks.size} chunks (${idx.embeddedChunks.size} embedded), strategy=${idx.strategy}, model=${idx.embeddingModel}")
        }
        AppTerminal.println("   /rag index [fixed|structural] | stats | compare | search <q> | on | off | eval | config")
    }

    // ── /rag index [fixed|structural] ──────────────────────────────────────────

    private suspend fun handleIndex(parts: List<String>) {
        val type = ChunkingStrategyType.fromString(parts.getOrNull(2) ?: config.defaultStrategy)
        val chunker = chunkerFor(type)
        val store = storeFor(type)
        AppTerminal.println("📚 Indexing corpus (${config.corpusRoots.joinToString(", ")}) with '${chunker.name}' strategy…")
        val docs = DocumentLoader(config.corpusRoots).load()
        if (docs.isEmpty()) {
            AppTerminal.warn("No documents found in corpus roots: ${config.corpusRoots.joinToString(", ")}")
            return
        }
        AppTerminal.println("   Loaded ${docs.size} documents.")
        val embedder = embedder()
        try {
            val indexer = RagIndexer(chunker, embedder, store)
            val index = AppTerminal.withSpinner("Indexing… (${chunker.name})") {
                indexer.index(docs) { done, total ->
                    // Прогресс печатается спиннер-фреймами; здесь только для лога (опционально).
                }
            }
            if (index == null) {
                AppTerminal.err("Indexing failed: Ollama error. See message above. Index not saved.")
                return
            }
            AppTerminal.ok("Indexed ${index.chunks.size} chunks (${index.embeddedChunks.size} embedded) → ${store.path()}")
            // День 22 (баг-фикс дня 21): дополнительно сохраняем в основной index.json — его читают
            // агент (RagRetriever), /rag search и /rag eval. Иначе index.json остаётся пустым, хотя
            // per-strategy файл (index-structural.json) создан → «не проиндексирован» при retrieval.
            val mainStore = JsonRagStore(AppPaths.ragIndexFile)
            mainStore.save(index)
            printStatsTable(index)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppTerminal.err("Indexing failed: ${e.message}")
        } finally {
            runCatching { embedder.close() }
        }
    }

    // ── /rag stats ─────────────────────────────────────────────────────────────

    private suspend fun handleStats(parts: List<String>) {
        val type = ChunkingStrategyType.fromString(parts.getOrNull(2) ?: config.defaultStrategy)
        val index = storeFor(type).load()
        if (index.chunks.isEmpty()) {
            AppTerminal.println("Index '${type.name.lowercase()}' is empty. Use: /rag index ${type.name.lowercase()}")
            return
        }
        printStatsTable(index)
    }

    // ── /rag compare ───────────────────────────────────────────────────────────

    private suspend fun handleCompare(parts: List<String>) {
        val docs = DocumentLoader(config.corpusRoots).load()
        if (docs.isEmpty()) {
            AppTerminal.warn("No documents found in corpus roots.")
            return
        }
        AppTerminal.println("📚 Comparing chunking strategies on ${docs.size} documents…")
        val embedder = embedder()
        try {
            val comparison = ChunkingComparison(
                FixedSizeChunker(config.chunkSizeTokens, config.chunkOverlapTokens),
                StructuralChunker(config.chunkSizeTokens, config.chunkOverlapTokens),
                embedder,
            )
            val report = AppTerminal.withSpinner("Building both indexes + probing…") {
                comparison.compare(docs)
            }
            if (report == null) {
                AppTerminal.err("Comparison failed: Ollama error.")
                return
            }
            printComparison(report)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppTerminal.err("Comparison failed: ${e.message}")
        } finally {
            runCatching { embedder.close() }
        }
    }

    // ── /rag search <query> ────────────────────────────────────────────────────

    private suspend fun handleSearch(parts: List<String>) {
        val query = parts.drop(2).joinToString(" ").trim()
        if (query.isEmpty()) {
            AppTerminal.println("Usage: /rag search <query>")
            return
        }
        val index = loadActiveIndex()
        if (index.embeddedChunks.isEmpty()) {
            AppTerminal.println("No embedded index. Use: /rag index first.")
            return
        }
        val embedder = embedder()
        try {
            val result = embedder.embed(listOf(query))
            if (result is com.cliagent.llm.LlmResult.Error) {
                AppTerminal.err("Embedding query failed: ${result.message}")
                return
            }
            @Suppress("UNCHECKED_CAST")
            val qVec = (result as com.cliagent.llm.LlmResult.Success).data.first()
            val hits = topK(qVec, index.chunks, k = 5)
            printSearchResults(query, hits)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppTerminal.err("Search failed: ${e.message}")
        } finally {
            runCatching { embedder.close() }
        }
    }

    // ── /rag config ────────────────────────────────────────────────────────────

    private fun printConfig() {
        AppTerminal.println("📚 RAG config:")
        AppTerminal.println("  enabled: ${config.enabled}")
        AppTerminal.println("  embeddingProvider: ${config.embeddingProvider}")
        AppTerminal.println("  embeddingModel: ${config.embeddingModel}")
        AppTerminal.println("  embeddingBaseUrl: ${config.embeddingBaseUrl}")
        AppTerminal.println("  corpusRoots: ${config.corpusRoots.joinToString(", ")}")
        AppTerminal.println("  chunkSizeTokens: ${config.chunkSizeTokens}")
        AppTerminal.println("  chunkOverlapTokens: ${config.chunkOverlapTokens}")
        AppTerminal.println("  defaultStrategy: ${config.defaultStrategy}")
        AppTerminal.println("  topK: ${config.topK}")
        AppTerminal.println("  injectIntoPrompt: ${config.injectIntoPrompt}")
        AppTerminal.println("  indexDir: ${AppPaths.ragDir}")
    }

    // ── /rag eval — прогон 10 контрольных вопросов (день 22) ───────────────────

    /**
     * Прогоняет контрольные вопросы (classpath: `rag/eval-questions.json`) в двух режимах — без RAG
     * и с RAG — и печатает сравнительный отчёт по покрытию expectedKeywords. Лекция недели 5:
     * метрика качества RAG — правдивость ответов (извлечение фактов из чанков, не из «общей памяти»).
     *
     * Требует собранного индекса (`/rag index`) и доступной Ollama для запроса ответов у LLM.
     */
    private suspend fun handleEval(parts: List<String>) {
        val index = loadActiveIndex()
        if (index.embeddedChunks.isEmpty()) {
            AppTerminal.warn("No embedded index. Use: /rag index first.")
            return
        }
        val questions = loadEvalQuestions()
        if (questions.isEmpty()) {
            AppTerminal.warn("No eval questions found (rag/eval-questions.json).")
            return
        }
        AppTerminal.println("📊 Running ${questions.size} control questions (RAG off vs on)…")
        val originalState = agent.isRagEnabled()

        val results = mutableListOf<EvalRow>()
        questions.forEachIndexed { i, q ->
            AppTerminal.println("\n[${i + 1}/${questions.size}] ${q.id}: ${q.question}")
            // Без RAG
            agent.setRagEnabled(false)
            val answerNoRag = runCatching { chat(q.question) }
                .getOrElse { "(error: ${it.message})" }
            // С RAG
            agent.setRagEnabled(true)
            val answerRag = runCatching { chat(q.question) }
                .getOrElse { "(error: ${it.message})" }
            val row = EvalRow(
                q,
                answerNoRag,
                answerRag,
                noRagHits = q.expectedKeywords.count { kw -> answerNoRag.contains(kw, ignoreCase = true) },
                ragHits = q.expectedKeywords.count { kw -> answerRag.contains(kw, ignoreCase = true) },
            )
            results.add(row)
            AppTerminal.println("  no-RAG keywords: ${row.noRagHits}/${q.expectedKeywords.size}  |  RAG keywords: ${row.ragHits}/${q.expectedKeywords.size}")
        }

        agent.setRagEnabled(originalState)
        printEvalReport(results)
    }

    private fun loadEvalQuestions(): List<EvalQuestion> {
        val json = Json { ignoreUnknownKeys = true }
        val raw = runCatching {
            javaClass.getResourceAsStream("/rag/eval-questions.json")?.use { it.readBytes() }
        }.getOrNull() ?: return emptyList()
        return runCatching {
            json.decodeFromString<List<EvalQuestion>>(String(raw, Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun printEvalReport(results: List<EvalRow>) {
        AppTerminal.println("\n${"─".repeat(60)}")
        AppTerminal.println("📊 RAG eval summary (${results.size} questions)")
        AppTerminal.println("${"─".repeat(60)}")
        val tbl = table {
            header { style(bold = true); row("Q", "no-RAG kw", "RAG kw", "Δ") }
            body {
                results.forEach { r ->
                    val delta = r.ragHits - r.noRagHits
                    val deltaStr = (if (delta >= 0) "+" else "") + delta
                    row(r.question.id, "${r.noRagHits}/${r.expectedKeywords.size}", "${r.ragHits}/${r.expectedKeywords.size}", deltaStr)
                }
            }
        }
        AppTerminal.println(tbl)
        val noRagTotal = results.sumOf { it.noRagHits }
        val ragTotal = results.sumOf { it.ragHits }
        val totalKw = results.sumOf { it.expectedKeywords.size }
        AppTerminal.println("Total keyword coverage: no-RAG $noRagTotal/$totalKw  |  RAG $ragTotal/$totalKw")
        AppTerminal.println("Δ total: ${if (ragTotal - noRagTotal >= 0) "+" else ""}${ragTotal - noRagTotal}")
        AppTerminal.println("\nInspect full answers per question with /rag on then asking directly.")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Грузит «активный» индекс для retrieval-команд (search, eval, summary). День 22 (баг-фикс дня 21):
     * сначала основной [AppPaths.ragIndexFile], при пустоте — per-strategy файл дефолтной стратегии
     * (старые индексы, созданные до фикса, лежат только там).
     */
    private suspend fun loadActiveIndex(): RagIndex {
        val primary = JsonRagStore(AppPaths.ragIndexFile).load()
        if (primary.embeddedChunks.isNotEmpty()) return primary
        return storeFor(ChunkingStrategyType.fromString(config.defaultStrategy)).load()
    }

    private fun chunkerFor(type: ChunkingStrategyType): ChunkingStrategy = when (type) {
        ChunkingStrategyType.FIXED -> FixedSizeChunker(config.chunkSizeTokens, config.chunkOverlapTokens)
        ChunkingStrategyType.STRUCTURAL -> StructuralChunker(config.chunkSizeTokens, config.chunkOverlapTokens)
    }

    /** Index-файл по стратегии: fixed → ragIndexFixed, structural → ragIndexStructural. */
    private fun storeFor(type: ChunkingStrategyType): JsonRagStore = JsonRagStore(
        when (type) {
            ChunkingStrategyType.FIXED -> AppPaths.ragIndexFixed
            ChunkingStrategyType.STRUCTURAL -> AppPaths.ragIndexStructural
        }
    )

    private fun printStatsTable(index: RagIndex) {
        val tokens = index.chunks.map { it.tokenCount }
        val tbl = table {
            captionTop("📚 RAG Index — ${index.strategy}")
            header { style(bold = true); row("Metric", "Value") }
            body {
                row("Strategy", index.strategy)
                row("Embedding model", index.embeddingModel.ifBlank { "(none)" })
                row("Dimension", "${index.dimension}")
                row("Documents", "${index.documents.size}")
                row("Chunks", "${index.chunks.size}")
                row("Embedded", "${index.embeddedChunks.size}")
                row("Tokens (min/avg/max)", "${tokens.minOrNull() ?: 0} / ${if (tokens.isEmpty()) 0 else tokens.average().toInt()} / ${tokens.maxOrNull() ?: 0}")
                row("Index file", AppPaths.ragIndexFile.fileName.toString())
            }
        }
        AppTerminal.println(tbl)
    }

    private fun printComparison(report: com.cliagent.rag.ComparisonReport) {
        val tbl = table {
            captionTop("📚 Chunking Strategy Comparison (${report.documents} docs)")
            header { style(bold = true); row("Metric", "Fixed", "Structural") }
            body {
                row("Chunks", "${report.fixed.chunkCount}", "${report.structural.chunkCount}")
                row("Embedded", "${report.fixed.embeddedCount}", "${report.structural.embeddedCount}")
                row("Tokens min", "${report.fixed.minTokens}", "${report.structural.minTokens}")
                row("Tokens avg", "${report.fixed.avgTokens}", "${report.structural.avgTokens}")
                row("Tokens max", "${report.fixed.maxTokens}", "${report.structural.maxTokens}")
                row("Dimension", "${report.fixed.dimension}", "${report.structural.dimension}")
            }
        }
        AppTerminal.println(tbl)

        AppTerminal.println()
        AppTerminal.println("🔍 Probe retrieval (top-3 per strategy):")
        report.probes.forEach { probe ->
            AppTerminal.println()
            AppTerminal.println("Q: ${probe.query}")
            AppTerminal.println("  [fixed]")
            probe.fixed.forEachIndexed { i, sc -> AppTerminal.println("    ${i + 1}. ${fmtScore(sc)} ${fmtChunk(sc.chunk)}") }
            AppTerminal.println("  [structural]")
            probe.structural.forEachIndexed { i, sc -> AppTerminal.println("    ${i + 1}. ${fmtScore(sc)} ${fmtChunk(sc.chunk)}") }
        }
    }

    private fun printSearchResults(query: String, hits: List<ScoredChunk>) {
        AppTerminal.println("🔍 Search: \"$query\" (top-${hits.size})")
        if (hits.isEmpty()) {
            AppTerminal.println("  No results.")
            return
        }
        hits.forEachIndexed { i, sc ->
            AppTerminal.println("  ${i + 1}. ${fmtScore(sc)} ${fmtChunk(sc.chunk)}")
            val preview = sc.chunk.text.take(200).replace("\n", " ")
            AppTerminal.println("     $preview…")
        }
    }

    private fun fmtScore(sc: ScoredChunk): String = String.format("%.3f", sc.score)

    private fun fmtChunk(chunk: RagChunk): String = "[${chunk.title} › ${chunk.section}] (${chunk.tokenCount} tok)"
}

/** Контрольный вопрос для `/rag eval` (classpath: `rag/eval-questions.json`). День 22. */
@Serializable
private data class EvalQuestion(
    val id: String,
    val question: String,
    val expectedKeywords: List<String> = emptyList(),
    val expectedSources: List<String> = emptyList(),
)

/** Результат прогона одного вопроса в двух режимах (без RAG / с RAG). */
private data class EvalRow(
    val question: EvalQuestion,
    val answerNoRag: String,
    val answerRag: String,
    val noRagHits: Int,
    val ragHits: Int,
) {
    val expectedKeywords: List<String> get() = question.expectedKeywords
}
