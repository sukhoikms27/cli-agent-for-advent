package com.cliagent.cli

import com.cliagent.config.AppPaths
import com.cliagent.llm.LlmClient
import com.cliagent.rag.ChunkingComparison
import com.cliagent.rag.DocumentLoader
import com.cliagent.rag.JsonRagStore
import com.cliagent.rag.RagChunk
import com.cliagent.rag.RagConfig
import com.cliagent.rag.RagIndex
import com.cliagent.rag.RagIndexer
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.ScoredChunk
import com.cliagent.rag.queryRewriterOf
import com.cliagent.rag.rerankerOf
import com.cliagent.rag.rerank.RerankerType
import com.cliagent.rag.rewrite.QueryRewriterType
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
 * - `/rag rewrite <identity|heuristic|llm>` — runtime-toggle query rewrite (день 23)
 * - `/rag rerank <none|threshold|heuristic|llm>` — runtime-toggle реранкера/фильтра (день 23)
 * - `/rag compare-modes` — A/B-сравнение матрицы режимов rerank×rewrite (день 23)
 *
 * @param agent агент для toggle on|off и eval-прогона
 * @param chat  chat-функция (statefulAgent.chat под спиннером) — для eval, чтобы RAG-инъекция
 *              шла через защищённый StatefulAgent (инварианты покрывают RAG-ответы)
 * @param ragRetriever retrieval для runtime-toggle rerank/rewrite (день 23)
 * @param llmClient LLM для LLM-режимов rewrite/rerank; null → деградация до identity/none
 * @param model имя LLM-модели (для LLM-режимов)
 */
internal class RagCommands(
    private val config: RagConfig,
    private val sharedEmbedder: OllamaEmbeddingClient,
    private val agent: com.cliagent.agent.ContextAwareAgent,
    private val chat: suspend (String) -> String,
    private val ragRetriever: RagRetriever,
    private val llmClient: LlmClient?,
    private val model: String,
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
            "compare-modes" -> handleCompareModes(parts)
            "search" -> handleSearch(parts)
            "on" -> { agent.setRagEnabled(true); AppTerminal.ok("RAG injection: ON") }
            "off" -> { agent.setRagEnabled(false); AppTerminal.ok("RAG injection: OFF") }
            "eval" -> handleEval(parts)
            "rewrite" -> handleRewrite(parts)
            "rerank" -> handleRerank(parts)
            "scenario" -> handleScenario(parts)
            "config" -> printConfig()
            else -> AppTerminal.println(
                "Unknown /rag command: ${parts[1]}. Use: index, stats, compare, compare-modes, search, on, off, eval, rewrite, rerank, scenario, config"
            )
        }
    }

    // ── /rag — сводка ──────────────────────────────────────────────────────────

    /** Сводка: runtime-режим агента (день 22), модель эмбеддинга, текущий индекс, rerank/rewrite (день 23). */
    private suspend fun printSummary() {
        val runtimeState = if (agent.isRagEnabled()) "ON" else "OFF"
        // День 23: runtime-режим rerank/rewrite (из RagRetriever), дефолт — из config.
        val rewriteName = ragRetriever.getRewriter()?.name ?: "identity"
        val rerankName = ragRetriever.getReranker()?.name ?: "none"
        AppTerminal.println("📚 RAG injection: $runtimeState  (config default: ${if (config.enabled) "ON" else "OFF"})")
        AppTerminal.println("   Embeddings: ${config.embeddingProvider} / ${config.embeddingModel} (${config.embeddingBaseUrl})")
        AppTerminal.println("   Corpus: ${config.corpusRoots.joinToString(", ")}")
        AppTerminal.println("   Chunking: size=${config.chunkSizeTokens} overlap=${config.chunkOverlapTokens}, topK=${config.topK}")
        AppTerminal.println("   Rewrite: $rewriteName  (config: ${config.queryRewriter})  |  Rerank: $rerankName  (config: ${config.reranker})")
        AppTerminal.println("   Candidate pool: ${config.candidatePoolSize}  |  Similarity threshold: ${String.format("%.2f", config.similarityThreshold)}")
        // День 24: порог анти-галлюцинации (canned «не знаю» при max similarity < порога).
        AppTerminal.println("   Anti-hallucination: dontKnowThreshold=${String.format("%.2f", config.dontKnowThreshold)}")
        AppTerminal.println("   Conversational query: ${config.conversationalQuery}   ← день 25")
        val idx = loadActiveIndex()
        if (idx.chunks.isEmpty()) {
            AppTerminal.println("   Index: empty. Use: /rag index")
        } else {
            AppTerminal.println("   Index: ${idx.chunks.size} chunks (${idx.embeddedChunks.size} embedded), strategy=${idx.strategy}, model=${idx.embeddingModel}")
        }
        AppTerminal.println("   /rag index [fixed|structural] | stats | compare | compare-modes | search <q> | on | off | eval | rewrite <type> | rerank <type> | scenario <name> | config")
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
        // День 23: реранкинг и фильтрация.
        AppTerminal.println("  candidatePoolSize: ${config.candidatePoolSize}")
        AppTerminal.println("  similarityThreshold: ${config.similarityThreshold}")
        AppTerminal.println("  queryRewriter: ${config.queryRewriter}")
        AppTerminal.println("  reranker: ${config.reranker}")
        // День 24: порог анти-галлюцинации («не знаю» при слабом контексте). 0.0 = выключено.
        AppTerminal.println("  dontKnowThreshold: ${config.dontKnowThreshold}")
        // День 25: conversation-aware retrieval (обогащение запроса целью + историей).
        AppTerminal.println("  conversationalQuery: ${config.conversationalQuery}")
        // День 23: runtime-режим (может отличаться от config после toggle).
        AppTerminal.println("  runtime rewrite: ${ragRetriever.getRewriter()?.name ?: "identity"}")
        AppTerminal.println("  runtime rerank: ${ragRetriever.getReranker()?.name ?: "none"}")
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
            // День 24: анти-галлюцинации — детекция источников/цитат в RAG-ответе. hits не передаём
            // (они внутри агента), детектируем по expectedSources (basename) и кавычкам в ответе.
            val cite = com.cliagent.rag.CitationDetector.detect(answerRag, emptyList(), q.expectedSources)
            val row = EvalRow(
                q,
                answerNoRag,
                answerRag,
                noRagHits = q.expectedKeywords.count { kw -> answerNoRag.contains(kw, ignoreCase = true) },
                ragHits = q.expectedKeywords.count { kw -> answerRag.contains(kw, ignoreCase = true) },
                ragSources = cite.sourcesPresent,
                ragCitations = cite.citationsPresent,
            )
            results.add(row)
            AppTerminal.println("  no-RAG kw: ${row.noRagHits}/${q.expectedKeywords.size}  |  RAG kw: ${row.ragHits}/${q.expectedKeywords.size}  |  src: ${if (row.ragSources) "✓" else "✗"}  cite: ${if (row.ragCitations) "✓" else "✗"}")
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
        AppTerminal.println("\n${"─".repeat(72)}")
        AppTerminal.println("📊 RAG eval summary (${results.size} questions)")
        AppTerminal.println("${"─".repeat(72)}")
        val tbl = table {
            header { style(bold = true); row("Q", "no-RAG kw", "RAG kw", "Δ", "RAG src", "RAG cite") }
            body {
                results.forEach { r ->
                    val delta = r.ragHits - r.noRagHits
                    val deltaStr = (if (delta >= 0) "+" else "") + delta
                    row(
                        r.question.id,
                        "${r.noRagHits}/${r.expectedKeywords.size}",
                        "${r.ragHits}/${r.expectedKeywords.size}",
                        deltaStr,
                        if (r.ragSources) "✓" else "✗",
                        if (r.ragCitations) "✓" else "✗",
                    )
                }
            }
        }
        AppTerminal.println(tbl)
        val noRagTotal = results.sumOf { it.noRagHits }
        val ragTotal = results.sumOf { it.ragHits }
        val totalKw = results.sumOf { it.expectedKeywords.size }
        AppTerminal.println("Total keyword coverage: no-RAG $noRagTotal/$totalKw  |  RAG $ragTotal/$totalKw")
        AppTerminal.println("Δ total: ${if (ragTotal - noRagTotal >= 0) "+" else ""}${ragTotal - noRagTotal}")
        // День 24: метрика анти-галлюцинаций — % RAG-ответов с источниками и цитатами (требование задания).
        val srcCount = results.count { it.ragSources }
        val citeCount = results.count { it.ragCitations }
        val n = results.size
        val srcPct = if (n > 0) srcCount * 100 / n else 0
        val citePct = if (n > 0) citeCount * 100 / n else 0
        AppTerminal.println("RAG answers with sources:   $srcCount/$n ($srcPct%)   ← день 24")
        AppTerminal.println("RAG answers with citations: $citeCount/$n ($citePct%)   ← день 24")
        AppTerminal.println("\nInspect full answers per question with /rag on then asking directly.")
    }

    /**
     * День 25 (TDD-рефакторинг): чистое ядро прогона сценария. БЕЗ [AppTerminal]/[loadActiveIndex]/[agent] —
     * только цикл по turns + [chat]-лямбда + [CitationDetector] + keyword-подсчёт. Возвращает rows с
     * ответами и метриками для печати/тестирования.
     *
     * Вынесено из [handleScenario] для тестируемости: тесты (TDD RED→GREEN) вызывают `runScenario`
     * напрямую с mock-лямбдой `chat` (без реальной LLM/Ollama), проверяя что:
     *  - chat вызывается по разу на turn с правильным `turn.user` (последовательность, без гонки);
     *  - ответ сохраняется в [ScenarioTurnRow.answer] (не теряется — главный баг до починки);
     *  - метрики (kw/src/cite) считаются из реального ответа, а не из пустой строки.
     *
     * `forEachIndexed` + suspend `chat` → строго последовательно (await каждого ответа перед следующим).
     *
     * @param scenario готовый сценарий (цель + turns с expectedKeywords/expectedSources).
     * @param chat chat-функция (statefulAgent.chat в проде; mock-лямбда в тестах).
     * @return по одной [ScenarioTurnRow] на turn, в порядке scenario.turns.
     */
    internal suspend fun runScenario(
        scenario: EvalScenario,
        chat: suspend (String) -> String,
    ): List<ScenarioTurnRow> {
        val rows = mutableListOf<ScenarioTurnRow>()
        scenario.turns.forEach { turn ->
            val answer = try {
                chat(turn.user)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                "(error: ${e.message})"
            }
            val cite = com.cliagent.rag.CitationDetector.detect(answer, emptyList(), turn.expectedSources)
            val kwHits = turn.expectedKeywords.count { kw -> answer.contains(kw, ignoreCase = true) }
            rows.add(
                ScenarioTurnRow(
                    user = turn.user,
                    answer = answer,
                    keywordHits = kwHits,
                    keywordTotal = turn.expectedKeywords.size,
                    sourcesPresent = cite.sourcesPresent,
                    citationsPresent = cite.citationsPresent,
                )
            )
        }
        return rows
    }

    // ── /rag scenario <name> (день 25 — production-like multi-turn validation) ───

    /**
     * Прогон scripted-сценария (день 25): список связанных реплик в одном диалоге с целью (память
     * задачи) + RAG каждый ход + проверка источников/цитат per-turn. Валидирует требование задания
     * «не теряет цель и продолжает выдавать ответы с источниками».
     *
     * Pipeline: `agent.reset()` (изоляция — чистит history/working, НЕ long-term) →
     * `setRagEnabled(true)` (RAG-on для сценария) → `setWorkingMemory(currentTask=goal)` (память
     * задачи) → прогон всех `turns` через `chat()` с персистентной историей → per-turn пост-чек
     * (CitationDetector + keywords) → сводный отчёт. Исходный RAG-режим восстанавливается в `finally`.
     */
    private suspend fun handleScenario(parts: List<String>) {
        val name = parts.getOrNull(2)?.trim()?.lowercase()
        if (name.isNullOrEmpty()) {
            AppTerminal.println("Usage: /rag scenario <name>")
            AppTerminal.println("  Available scenarios: ${listScenarios().joinToString(", ")}")
            return
        }
        val scenario = loadScenario(name)
        if (scenario == null) {
            AppTerminal.warn("Scenario '$name' not found. Available: ${listScenarios().joinToString(", ")}")
            return
        }
        val index = loadActiveIndex()
        if (index.embeddedChunks.isEmpty()) {
            AppTerminal.warn("No embedded index. Use: /rag index first (scenarios require RAG).")
            return
        }

        AppTerminal.println("🎬 Scenario: ${scenario.id} (${scenario.turns.size} turns)")
        AppTerminal.println("   Goal: ${scenario.goal}")
        // Изоляция + память задачи: reset чистит history/working (НЕ long-term), затем ставим цель.
        agent.reset()
        val savedRag = agent.isRagEnabled()
        val savedConv = agent.isConversationalQuery()
        agent.setRagEnabled(true)
        // День 25: форсируем conversation-aware retrieval в сценарии — follow-up реплики
        // («а сколько для этого нужно?») должны находить контекст. Восстанавливаем в finally.
        agent.setConversationalQuery(true)
        agent.setWorkingMemory(com.cliagent.memory.WorkingMemory(currentTask = scenario.goal))
        // T6: диагностика — если RAG реально не активировался (нет retriever'а), пользователь должен знать.
        if (!agent.isRagEnabled()) {
            AppTerminal.warn("RAG не активен (нет retriever'а) — ответы идут без retrieval/sources.")
        }

        try {
            // Прогон через чистое ядро [runScenario] (TDD-тестируемое, без AppTerminal/диска).
            val rows = runScenario(scenario, chat)
            // Печать per-turn с ПОЛНЫМ ответом агента (главный фикс: раньше ответ не печатался,
            // и метрики без контекста были бессмысленны). Ответ = первичный артефакт демо-чата.
            rows.forEachIndexed { i, r ->
                AppTerminal.println("\n[${i + 1}/${rows.size}] ${r.user}")
                AppTerminal.println("─".repeat(60))
                AppTerminal.markdown(r.answer)
                AppTerminal.println("─".repeat(60))
                AppTerminal.println("  kw: ${r.keywordHits}/${r.keywordTotal}  |  src: ${if (r.sourcesPresent) "✓" else "✗"}  |  cite: ${if (r.citationsPresent) "✓" else "✗"}")
            }
            printScenarioReport(scenario, rows)
        } catch (e: CancellationException) {
            throw e
        } finally {
            // Восстанавливаем исходные RAG-режимы (history/working — изолированы reset'ом, не трогаем).
            agent.setRagEnabled(savedRag)
            agent.setConversationalQuery(savedConv)
        }
    }

    /** Грузит сценарий из classpath `/rag/scenarios/<name>.json` (по образцу [loadEvalQuestions]). */
    private fun loadScenario(name: String): EvalScenario? {
        val json = Json { ignoreUnknownKeys = true }
        val raw = runCatching {
            javaClass.getResourceAsStream("/rag/scenarios/$name.json")?.use { it.readBytes() }
        }.getOrNull() ?: return null
        return runCatching {
            json.decodeFromString<EvalScenario>(String(raw, Charsets.UTF_8))
        }.getOrNull()
    }

    /** Список доступных сценариев из classpath `rag/scenarios/` (для `/rag scenario` без аргумента). */
    private fun listScenarios(): List<String> {
        // Classpath-директория не всегда перечисляется единообразно; фиксируем известные сценарии.
        return listOf("rag-architecture", "task-fsm")
    }

    private suspend fun printScenarioReport(scenario: EvalScenario, rows: List<ScenarioTurnRow>) {
        AppTerminal.println("\n${"─".repeat(72)}")
        AppTerminal.println("🎬 Scenario report: ${scenario.id} (${rows.size} turns)")
        AppTerminal.println("   Goal: ${scenario.goal}")
        AppTerminal.println("${"─".repeat(72)}")
        val tbl = table {
            header { style(bold = true); row("#", "kw", "src", "cite") }
            body {
                rows.forEachIndexed { i, r ->
                    row(
                        "${i + 1}",
                        "${r.keywordHits}/${r.keywordTotal}",
                        if (r.sourcesPresent) "✓" else "✗",
                        if (r.citationsPresent) "✓" else "✗",
                    )
                }
            }
        }
        AppTerminal.println(tbl)
        val n = rows.size
        val srcCount = rows.count { it.sourcesPresent }
        val citeCount = rows.count { it.citationsPresent }
        val srcPct = if (n > 0) srcCount * 100 / n else 0
        val citePct = if (n > 0) citeCount * 100 / n else 0
        AppTerminal.println("Sources in turns:   $srcCount/$n ($srcPct%)   ← день 25")
        AppTerminal.println("Citations in turns: $citeCount/$n ($citePct%)   ← день 25")
        // Семантический goal-retention (T5): не просто «currentTask не изменился» (тривиально), а
        // «финальный ответ держит тему цели». Проверяем наличие ключевых слов цели в последнем ответе.
        val lastAnswer = rows.lastOrNull()?.answer.orEmpty().lowercase()
        val goalTerms = extractGoalTerms(scenario.goal)
        val goalRetained = goalTerms.isNotEmpty() && goalTerms.any { term -> lastAnswer.contains(term.lowercase()) }
        val goalReason = when {
            goalTerms.isEmpty() -> "(нет извлекаемых терминов цели)"
            goalRetained -> "✓ финальный ответ содержит тему цели (${goalTerms.joinToString("/")})"
            else -> "✗ финальный ответ не содержит терминов цели (${goalTerms.joinToString("/")})"
        }
        AppTerminal.println("Goal retained: ${if (goalRetained) "✓" else "✗"}  $goalReason   ← день 25 (память задачи)")
        AppTerminal.println("\nОтветы агента показаны выше per-turn; метрики — валидация источников/цитат.")
    }

    /**
     * Извлекает ключевые термины из текста цели сценария для семантической проверки goal-retention.
     * Берёт слова длиной ≥4 (отсекает стоп-слова/короткие), нормализует к нижнему регистру.
     */
    private fun extractGoalTerms(goal: String): List<String> {
        return goal.split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 4 }
            .map { it.lowercase() }
            .distinct()
            .take(8)   // ограничиваем, чтобы матч был реалистичным
    }

    // ── /rag rewrite <identity|heuristic|llm> (день 23) ────────────────────────

    /** Runtime-toggle query rewrite. Создаёт rewriter через фабрику → `ragRetriever.setRewriter`. */
    private fun handleRewrite(parts: List<String>) {
        val type = parts.getOrNull(2)?.trim()?.lowercase()
        if (type.isNullOrEmpty()) {
            AppTerminal.println("Usage: /rag rewrite <identity|heuristic|llm>")
            AppTerminal.println("  Current: ${ragRetriever.getRewriter()?.name ?: "identity"}")
            return
        }
        // Валидация типа ДО фабрики (чтобы не молча деградировать до identity при опечатке).
        val resolved = QueryRewriterType.fromString(type)
        if (resolved == QueryRewriterType.LLM && llmClient == null) {
            AppTerminal.warn("LLM client unavailable — 'llm' rewrite degrades to identity.")
        }
        val rewriter = queryRewriterOf(type, llmClient, model)
        ragRetriever.setRewriter(rewriter)
        AppTerminal.ok("RAG rewrite: ${rewriter.name}")
    }

    // ── /rag rerank <none|threshold|heuristic|llm> (день 23) ───────────────────

    /** Runtime-toggle реранкера/фильтра. Создаёт reranker через фабрику → `ragRetriever.setReranker`. */
    private fun handleRerank(parts: List<String>) {
        val type = parts.getOrNull(2)?.trim()?.lowercase()
        if (type.isNullOrEmpty()) {
            AppTerminal.println("Usage: /rag rerank <none|threshold|heuristic|llm>")
            AppTerminal.println("  Current: ${ragRetriever.getReranker()?.name ?: "none"}")
            return
        }
        val resolved = RerankerType.fromString(type)
        if (resolved == RerankerType.LLM && llmClient == null) {
            AppTerminal.warn("LLM client unavailable — 'llm' rerank disabled (none).")
        }
        val reranker = rerankerOf(type, config, llmClient, model)
        ragRetriever.setReranker(reranker)
        AppTerminal.ok("RAG rerank: ${reranker?.name ?: "none"}")
    }

    // ── /rag compare-modes (день 23) ───────────────────────────────────────────

    /**
     * A/B-сравнение матрицы режимов rerank × rewrite (день 23, требование «сравните качество без
     * фильтра/rewriting и с фильтром»). Лекция недели 5: метрика — правдивость ответов (покрытие
     * expectedKeywords из контрольных вопросов).
     *
     * Для каждой пары (rewrite, rerank) прогоняет все eval-вопросы через `chat()` и считает покрытие.
     * RAG должен быть включён (иначе режимы не влияют на ответ). Сохраняет/восстанавливает исходный
     * runtime-режим RagRetriever — side-effect только в виде отчёта.
     */
    private suspend fun handleCompareModes(parts: List<String>) {
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
        // Матрица режимов. none/identity — baseline (день 22); остальные — день 23.
        val rewriters = listOf("identity", "heuristic", "llm")
        val rerankers = listOf("none", "threshold", "heuristic", "llm")

        AppTerminal.println("📊 Compare-modes: ${rewriters.size}×${rerankers.size}=${rewriters.size * rerankers.size} modes × ${questions.size} questions…")
        AppTerminal.println("   (RAG must be ON for modes to affect answers)")

        // Сохраняем исходный runtime-режим, чтобы восстановить после прогона.
        val savedRewriter = ragRetriever.getRewriter()
        val savedReranker = ragRetriever.getReranker()
        val savedRagState = agent.isRagEnabled()
        agent.setRagEnabled(true)

        try {
            // modeHits[modeLabel] = total keyword hits across all questions
            val modeHits = LinkedHashMap<String, Int>()
            val modeKeyTotals = LinkedHashMap<String, Int>()
            for (rw in rewriters) {
                for (rr in rerankers) {
                    val label = "rw=$rw|rr=$rr"
                    ragRetriever.setRewriter(queryRewriterOf(rw, llmClient, model))
                    ragRetriever.setReranker(rerankerOf(rr, config, llmClient, model))
                    var hits = 0
                    var totalKw = 0
                    questions.forEach { q ->
                        totalKw += q.expectedKeywords.size
                        val answer = runCatching { chat(q.question) }.getOrElse { "(error: ${it.message})" }
                        hits += q.expectedKeywords.count { kw -> answer.contains(kw, ignoreCase = true) }
                    }
                    modeHits[label] = hits
                    modeKeyTotals[label] = totalKw
                    AppTerminal.println("  $label → $hits/$totalKw keywords")
                }
            }
            printCompareModesReport(rewriters, rerankers, modeHits, modeKeyTotals)
        } catch (e: CancellationException) {
            throw e
        } finally {
            // Восстанавливаем исходный runtime-режим.
            ragRetriever.setRewriter(savedRewriter)
            ragRetriever.setReranker(savedReranker)
            agent.setRagEnabled(savedRagState)
        }
    }

    private fun printCompareModesReport(
        rewriters: List<String>,
        rerankers: List<String>,
        modeHits: Map<String, Int>,
        modeKeyTotals: Map<String, Int>,
    ) {
        AppTerminal.println("\n${"─".repeat(72)}")
        AppTerminal.println("📊 RAG compare-modes (keyword coverage by rewrite×rerank)")
        AppTerminal.println("${"─".repeat(72)}")
        val tbl = table {
            header {
                style(bold = true)
                row("rewrite \\ rerank", *rerankers.toTypedArray())
            }
            body {
                rewriters.forEach { rw ->
                    val cells = rerankers.map { rr ->
                        val label = "rw=$rw|rr=$rr"
                        val hits = modeHits[label] ?: 0
                        val total = modeKeyTotals[label] ?: 1
                        "$hits/$total"
                    }
                    row(rw, *cells.toTypedArray())
                }
            }
        }
        AppTerminal.println(tbl)
        // Подсветка лучшего режима.
        val best = modeHits.maxByOrNull { it.value }
        if (best != null) {
            val total = modeKeyTotals[best.key] ?: 1
            AppTerminal.println("Best mode: ${best.key} → ${best.value}/$total keywords")
        }
        val baselineKey = "rw=identity|rr=none"
        val baselineHits = modeHits[baselineKey] ?: 0
        AppTerminal.println("Baseline ($baselineKey): $baselineHits/${modeKeyTotals[baselineKey] ?: 0}")
        AppTerminal.println("\nHigher keyword coverage = better retrieval quality (lecture week 5 metric).")
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
    // День 24: анти-галлюцинации — источники и цитаты в RAG-ответе (CitationDetector).
    val ragSources: Boolean,
    val ragCitations: Boolean,
) {
    val expectedKeywords: List<String> get() = question.expectedKeywords
}

// ── День 25: scripted-сценарии (multi-turn validation «production-like») ───────────

/**
 * Контрольный сценарий для `/rag scenario` (день 25). Список связанных реплик, прогоняемых в одном
 * диалоге с персистентной историей и целью (память задачи). Проверяет, что ассистент не теряет цель
 * и продолжает выдавать ответы с источниками на follow-up реплики.
 *
 * @param id   идентификатор сценария (имя JSON-файла без расширения в `rag/scenarios/`).
 * @param goal цель диалога → `WorkingMemory.currentTask` (память задачи). Задаётся в harness перед
 *             прогоном; используется conversation-aware retrieval для интерпретации follow-up.
 * @param turns реплики пользователя по порядку; поздние могут ссылаться на ранние (follow-up).
 */
@Serializable
internal data class EvalScenario(
    val id: String,
    val goal: String,
    val turns: List<EvalScenarioTurn>,
)

/** Одна реплика сценария с ожиданиями для post-check (по образцу [EvalQuestion], день 22). */
@Serializable
internal data class EvalScenarioTurn(
    val user: String,
    val expectedKeywords: List<String> = emptyList(),
    val expectedSources: List<String> = emptyList(),
)

/**
 * Результат прогона одной реплики сценария. `internal` (не private) — для тестов модуля (TDD):
 * [runScenario] возвращает список этих строк, тесты проверяют [answer]/[keywordHits]/etc напрямую,
 * без перехвата вывода [AppTerminal].
 */
internal data class ScenarioTurnRow(
    val user: String,
    val answer: String,
    val keywordHits: Int,
    val keywordTotal: Int,
    val sourcesPresent: Boolean,
    val citationsPresent: Boolean,
)
