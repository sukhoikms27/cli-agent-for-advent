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

/**
 * День 21: обработчик slash-команды `/rag` (индексация документов). Зеркалирует структуру
 * `handleMcp` в [ChatCommand]: разбор частей → dispatch по подкоманде.
 *
 * **Важно:** RAG опционален. Без установленной Ollama команды индексации деградируют с понятным
 * сообщением; остальной агент не страдает. День 22 добавит инъекцию retrieved-чанков в промпт.
 *
 * Команды:
 * - `/rag` — сводка (статус Ollama + текущий индекс)
 * - `/rag index [fixed|structural]` — переиндексация корпуса выбранной стратегией
 * - `/rag stats` — статистика текущего индекса (mordant-таблица)
 * - `/rag compare` — построить оба индекса + сравнительная таблица + пробный retrieval
 * - `/rag search <query>` — пробный retrieval top-5 (без агента; smoke-test)
 * - `/rag config` — показать RagConfig
 */
internal class RagCommands(
    private val config: RagConfig,
) {

    /** Ленивый embedder — создаётся только при первом индексирующем запросе (не на старте REPL). */
    private fun embedder(): OllamaEmbeddingClient = OllamaEmbeddingClient(
        baseUrl = config.embeddingBaseUrl,
        model = config.embeddingModel,
    )

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
            "config" -> printConfig()
            else -> AppTerminal.println(
                "Unknown /rag command: ${parts[1]}. Use: index, stats, compare, search, config"
            )
        }
    }

    // ── /rag — сводка ──────────────────────────────────────────────────────────

    /** Сводка: статус RAG, модель эмбеддинга, текущий индекс. */
    private suspend fun printSummary() {
        AppTerminal.println("📚 RAG: ${if (config.enabled) "ON" else "OFF (indexing-only; agent injection = day 22)"}")
        AppTerminal.println("   Embeddings: ${config.embeddingProvider} / ${config.embeddingModel} (${config.embeddingBaseUrl})")
        AppTerminal.println("   Corpus: ${config.corpusRoots.joinToString(", ")}")
        AppTerminal.println("   Chunking: size=${config.chunkSizeTokens} overlap=${config.chunkOverlapTokens}")
        val store = JsonRagStore(AppPaths.ragIndexFile)
        val idx = store.load()
        if (idx.chunks.isEmpty()) {
            AppTerminal.println("   Index: empty. Use: /rag index")
        } else {
            AppTerminal.println("   Index: ${idx.chunks.size} chunks (${idx.embeddedChunks.size} embedded), strategy=${idx.strategy}, model=${idx.embeddingModel}")
        }
        AppTerminal.println("   /rag index [fixed|structural] | stats | compare | search <q> | config")
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
        val index = JsonRagStore(AppPaths.ragIndexFile).load()
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
        AppTerminal.println("  indexDir: ${AppPaths.ragDir}")
    }

    // ── helpers ────────────────────────────────────────────────────────────────

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
