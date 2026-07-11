package com.cliagent.cli

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.rag.CitationDetector
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.ScoredChunk
import com.github.ajalt.mordant.table.table
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * День 28: side-by-side сравнение local vs cloud LLM на ОДНОМ retrieved-контексте (fair compare).
 *
 * Задание (`plan/newdays/day28.md`): «Локальная LLM + RAG. Retrieval локально (Ollama), генерация
 * через локальную модель. Сравните local vs cloud. Оцените качество/скорость/стабильность.»
 *
 * Подход (опция MED из Research, вариант B1): вынести harness в отдельный объект, чтобы:
 *  - fair compare: контекст извлекается ОДИН раз через [RagRetriever.retrieve] (общая Ollama-эмбеддинг
 *    база), затем ОДИНАКОВЫЙ промпт (system + [Retrieved context] + question) скармливается обоим
 *    клиентам. Разница в ответах → только генеративная способность модели, не retrieval;
 *  - метрики качества: keyword coverage (по expectedKeywords) + citation score (CitationDetector —
 *    источники/цитаты, как в `/rag eval` дня 24) + длина ответа;
 *  - метрики скорости: latency через `System.currentTimeMillis()` (паттерн BenchmarkRunner/LocalSmoke);
 *  - метрики стабильности: фиксация ошибок (LlmResult.Error → "ERROR", прогон продолжается).
 *
 * `cloudClient == null` → режим local-only (cloud API key отсутствует): отчёт с local-колонками + warn.
 *
 * НЕ использует агента/history — прямой `client.chat` (как [LocalSmoke] и [BenchmarkRunner]), чтобы
 * изолировать генеративную модель от stateful-логики (memory/tools/invariants). RAG-контекст строится
 * вручную по образцу [com.cliagent.agent.renderRetrievedBlock] (PromptBuilder дня 22/24).
 *
 * `CancellationException` не глотается (AGENTS.md) — пробрасывается, прерывая прогон.
 */
object LocalRagCompare {

    /**
     * Простой вопрос для сравнения (совместим по полям с `rag/eval-questions.json` — id/question/
     * expectedKeywords/expectedSources). Отдельный data class здесь, чтобы harness не зависел от
     * private-классов [RagCommands]; CLI handler маппит [EvalQuestion] → [CompareQuestion].
     */
    @Serializable
    data class CompareQuestion(
        val id: String,
        val question: String,
        val expectedKeywords: List<String> = emptyList(),
        val expectedSources: List<String> = emptyList(),
    )

    /** Метрики одного прогона (local ИЛИ cloud) для одного вопроса. */
    data class SideMetrics(
        val answer: String,
        /** latency в мс (>= 0). */
        val latencyMs: Long,
        /** prompt tokens из usage (может быть 0/null для Ollama). */
        val promptTokens: Int,
        /** completion tokens из usage. */
        val completionTokens: Int,
        /** Покрытие expectedKeywords в ответе. */
        val keywordHits: Int,
        /** Всего expectedKeywords (для доли). */
        val keywordTotal: Int,
        /** CitationDetector: упомянут ли хотя бы один источник (basename). */
        val sourcesPresent: Boolean,
        /** CitationDetector: есть ли кавычки/overlap (дословное цитирование чанка). */
        val citationsPresent: Boolean,
        /** Длина ответа в символах (косвенный показатель полноты/лаконичности). */
        val length: Int,
        /** true если [answer] начинается с "ERROR" (LlmResult.Error или throwable). */
        val isError: Boolean,
    )

    /**
     * Результат сравнения одного вопроса: retrieved-контекст + метрики local/cloud.
     * [cloud] == null → вопрос пропущен (нет контекста) ИЛИ режим local-only (cloudClient=null).
     */
    data class CompareResult(
        val question: CompareQuestion,
        /** retrieved-чанки для fair compare (могут быть пустыми — но тогда всё равно прогоняем). */
        val hits: List<ScoredChunk>?,
        val local: SideMetrics,
        val cloud: SideMetrics?,
    )

    /**
     * Прогоняет [questions] в режиме fair compare: для каждого вопроса ОДИН retrieve (общий контекст),
     * затем ОДИНАКОВЫЙ промпт скармливается [localClient] и (опционально) [cloudClient]. Печатает
     * per-question строки и сводную mordant-таблицу side-by-side.
     *
     * @param questions контрольные вопросы (id/question/keywords/sources).
     * @param retriever RAG-retrieval (локальная Ollama-эмбеддинг база; retrieve ОДИН раз на вопрос).
     * @param localClient локальная LLM (qwen3:14b через Ollama). Прямой chat без агента.
     * @param localModel имя локальной модели.
     * @param cloudClient облачная LLM (glm-5.x); null → local-only режим (warn, cloud-колонки "n/a").
     * @param cloudModel имя облачной модели (для заголовка таблицы; null-безопасно при local-only).
     * @param systemPrompt базовый system-промпт (по умолчанию RAG-инструкция с требованием источников/
     *   цитат, как в [com.cliagent.agent.renderRetrievedBlock] дня 24 — fair: обе модели получают
     *   одинаковую инструкцию).
     * @return список [CompareResult] (по одному на вопрос, в порядке [questions]).
     */
    suspend fun runCompare(
        questions: List<CompareQuestion>,
        retriever: RagRetriever,
        localClient: LlmClient,
        localModel: String,
        cloudClient: LlmClient?,
        cloudModel: String?,
        systemPrompt: String = DEFAULT_RAG_SYSTEM_PROMPT,
    ): List<CompareResult> {
        if (cloudClient == null) {
            AppTerminal.warn("Cloud недоступен (нет API key) — compare в local-only режиме.")
        } else {
            AppTerminal.println("📊 Local vs Cloud RAG compare: ${questions.size} questions × 2 models")
            AppTerminal.println("   local: $localModel  |  cloud: $cloudModel")
        }

        val results = mutableListOf<CompareResult>()
        questions.forEachIndexed { i, q ->
            AppTerminal.println("\n[${i + 1}/${questions.size}] ${q.id}: ${q.question}")
            // Fair compare: retrieval ОДИН раз (общая Ollama-эмбеддинг база).
            val hits = try {
                retriever.retrieve(q.question)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppTerminal.warn("  retrieve failed for ${q.id}: ${e.message}")
                null
            }
            if (hits == null) {
                AppTerminal.println("  (no context — index empty or Ollama embedding error; skipping compare)")
                // Прогон всё же делаем БЕЗ контекста (fair: обе модели в одинаковом «слепом» режиме),
                // чтобы сравнить baseline-генерацию. Помечаем hits=null в результате.
            }

            val contextBlock = buildContextBlock(hits)
            val prompt = buildPrompt(systemPrompt, contextBlock, q.question)

            // LOCAL: maxTokens=2048 — фикс дня 26 (qwen3 thinking-модель тратит токены на <think>
            // ДО ответа; без явного max_tokens Ollama обрывает рано → content пустой).
            val localMetrics = measure(localClient, localModel, prompt, q, hits, maxTokens = 2048)
            AppTerminal.println(
                "  local: ${fmtLatency(localMetrics)}  kw ${localMetrics.keywordHits}/${localMetrics.keywordTotal}  " +
                    "src ${fmtFlag(localMetrics.sourcesPresent)}  cite ${fmtFlag(localMetrics.citationsPresent)}  " +
                    "len ${localMetrics.length}"
            )

            val cloudMetrics = if (cloudClient != null) {
                // Cloud может позволить больше токенов (или null — provider default).
                measure(cloudClient, cloudModel ?: "cloud", prompt, q, hits, maxTokens = null).also { cm ->
                    AppTerminal.println(
                        "  cloud: ${fmtLatency(cm)}  kw ${cm.keywordHits}/${cm.keywordTotal}  " +
                            "src ${fmtFlag(cm.sourcesPresent)}  cite ${fmtFlag(cm.citationsPresent)}  " +
                            "len ${cm.length}"
                    )
                }
            } else {
                null
            }

            results.add(
                CompareResult(
                    question = q,
                    hits = hits,
                    local = localMetrics,
                    cloud = cloudMetrics,
                )
            )
        }

        printSummary(results, localModel, cloudModel, cloudClient != null)
        return results
    }

    // ── measurement helpers ───────────────────────────────────────────────────

    /**
     * Прогон одного промпта через [client] с замером latency и снятием метрик качества.
     * `CancellationException` re-throw; прочие ошибки (throwable / LlmResult.Error) → [SideMetrics]
     * с isError=true, answer="ERROR: ...", метрики 0. Прогон продолжается (стабильность: один сбой
     * не роняет весь compare).
     */
    private suspend fun measure(
        client: LlmClient,
        model: String,
        prompt: String,
        q: CompareQuestion,
        hits: List<ScoredChunk>?,
        maxTokens: Int?,
    ): SideMetrics {
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "system", content = DEFAULT_RAG_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = prompt)),
            temperature = 0.0,
            maxTokens = maxTokens,
        )
        val start = System.currentTimeMillis()
        val result = try {
            client.chat(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            return errorMetrics("ERROR: ${e.message}", elapsed, q, hits)
        }
        val elapsed = System.currentTimeMillis() - start
        return when (result) {
            is LlmResult.Success -> {
                val usage = result.data.usage
                val text = result.data.choices.firstOrNull()?.message?.content ?: "(empty)"
                successMetrics(text, elapsed, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, q, hits)
            }
            is LlmResult.Error -> errorMetrics("ERROR: ${result.message}", elapsed, q, hits)
        }
    }

    /** Считает quality-метрики (keyword coverage + citation) из успешного ответа. */
    private fun successMetrics(
        answer: String,
        latencyMs: Long,
        promptTokens: Int,
        completionTokens: Int,
        q: CompareQuestion,
        hits: List<ScoredChunk>?,
    ): SideMetrics {
        val kwTotal = q.expectedKeywords.size
        val kwHits = q.expectedKeywords.count { kw -> answer.contains(kw, ignoreCase = true) }
        val cite = CitationDetector.detect(answer, hits ?: emptyList(), q.expectedSources)
        return SideMetrics(
            answer = answer,
            latencyMs = latencyMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            keywordHits = kwHits,
            keywordTotal = kwTotal,
            sourcesPresent = cite.sourcesPresent,
            citationsPresent = cite.citationsPresent,
            length = answer.length,
            isError = false,
        )
    }

    /** Метрики для ошибочного ответа (throwable / LlmResult.Error). */
    private fun errorMetrics(
        answer: String,
        latencyMs: Long,
        q: CompareQuestion,
        hits: List<ScoredChunk>?,
    ): SideMetrics = SideMetrics(
        answer = answer,
        latencyMs = latencyMs,
        promptTokens = 0,
        completionTokens = 0,
        keywordHits = 0,
        keywordTotal = q.expectedKeywords.size,
        sourcesPresent = false,
        citationsPresent = false,
        length = answer.length,
        isError = true,
    )

    // ── prompt building (по образцу PromptBuilder.renderRetrievedBlock дня 22/24) ─

    /**
     * Блок `[Retrieved context]` для инъекции в промпт — та же структура, что агент использует в
     * [com.cliagent.agent.renderRetrievedBlock] (fair: обе модели видят идентичный контекст).
     */
    private fun buildContextBlock(hits: List<ScoredChunk>?): String {
        if (hits.isNullOrEmpty()) return "[Retrieved context — нет релевантных чанков]"
        val lines = mutableListOf<String>()
        lines.add("[Retrieved context — ответь СТРОГО по этим источникам, не выдумывай.]")
        lines.add("Найденные чанки:")
        hits.forEachIndexed { i, sc ->
            val chunk = sc.chunk
            lines.add("${i + 1}. ${chunk.text}")
            lines.add("   — Source: ${chunk.source} › ${chunk.section} (${chunk.chunkId}, score ${String.format("%.3f", sc.score)})")
        }
        return lines.joinToString("\n")
    }

    /** Сборка user-промпта: контекст + вопрос (system идёт отдельно в ChatRequest). */
    private fun buildPrompt(systemPrompt: String, contextBlock: String, question: String): String =
        "$contextBlock\n\nВопрос: $question"

    // ── summary table (mordant, по образцу printEvalReport / LocalSmoke) ─────────

    private fun printSummary(
        results: List<CompareResult>,
        localModel: String,
        cloudModel: String?,
        hasCloud: Boolean,
    ) {
        AppTerminal.println("\n${"─".repeat(72)}")
        AppTerminal.println("📊 Local vs Cloud RAG compare summary (${results.size} questions)")
        AppTerminal.println("   local: $localModel" + (if (hasCloud) "  |  cloud: $cloudModel" else "  |  cloud: n/a (local-only)"))
        AppTerminal.println("${"─".repeat(72)}")

        if (hasCloud) {
            val tbl = table {
                header {
                    style(bold = true)
                    row("Q", "L ms", "L kw", "L src", "C ms", "C kw", "C src", "Δkw")
                }
                body {
                    results.forEach { r ->
                        val l = r.local
                        val c = r.cloud!!
                        val delta = (l.keywordHits - c.keywordHits)
                        val deltaStr = (if (delta >= 0) "+" else "") + delta
                        row(
                            r.question.id,
                            "${l.latencyMs}",
                            "${l.keywordHits}/${l.keywordTotal}",
                            if (l.sourcesPresent) "✓" else "✗",
                            "${c.latencyMs}",
                            "${c.keywordHits}/${c.keywordTotal}",
                            if (c.sourcesPresent) "✓" else "✗",
                            deltaStr,
                        )
                    }
                }
            }
            AppTerminal.println(tbl)
        } else {
            val tbl = table {
                header {
                    style(bold = true)
                    row("Q", "L ms", "L kw", "L src", "L cite", "L len")
                }
                body {
                    results.forEach { r ->
                        val l = r.local
                        row(
                            r.question.id,
                            "${l.latencyMs}",
                            "${l.keywordHits}/${l.keywordTotal}",
                            if (l.sourcesPresent) "✓" else "✗",
                            if (l.citationsPresent) "✓" else "✗",
                            "${l.length}",
                        )
                    }
                }
            }
            AppTerminal.println(tbl)
        }

        // Сводные totals: latency (avg), keyword coverage, errors (стабильность).
        val n = results.size
        if (n == 0) return
        val localAvgMs = results.sumOf { it.local.latencyMs } / n
        val localKwTotal = results.sumOf { it.local.keywordTotal }
        val localKwHits = results.sumOf { it.local.keywordHits }
        val localErrors = results.count { it.local.isError }
        val localSrc = results.count { it.local.sourcesPresent }
        AppTerminal.println("LOCAL avg latency: ${localAvgMs}ms  |  kw $localKwHits/$localKwTotal  |  src $localSrc/$n  |  errors $localErrors/$n")
        if (hasCloud) {
            val cloudAvgMs = results.sumOf { it.cloud?.latencyMs ?: 0 } / n
            val cloudKwTotal = results.sumOf { it.cloud?.keywordTotal ?: 0 }
            val cloudKwHits = results.sumOf { it.cloud?.keywordHits ?: 0 }
            val cloudErrors = results.count { it.cloud?.isError == true }
            val cloudSrc = results.count { it.cloud?.sourcesPresent == true }
            AppTerminal.println("CLOUD avg latency: ${cloudAvgMs}ms  |  kw $cloudKwHits/$cloudKwTotal  |  src $cloudSrc/$n  |  errors $cloudErrors/$n")
            // Скорость: local vs cloud (Δ). Отрицательный Δ = local быстрее.
            AppTerminal.println("Speed Δ (local − cloud): ${localAvgMs - cloudAvgMs}ms avg  (${if (localAvgMs <= cloudAvgMs) "local быстрее/равно" else "cloud быстрее"})")
            // Качество: Δ keyword coverage (local − cloud).
            val kwDelta = localKwHits - cloudKwHits
            AppTerminal.println("Quality Δ (local − cloud kw): ${if (kwDelta >= 0) "+" else ""}$kwDelta  (${if (kwDelta >= 0) "local ≥ cloud" else "cloud > local"})")
        }
        AppTerminal.println("\nΔkw > 0 = local лучше по keyword coverage. Меньшая latency = быстрее. errors — стабильность.")
    }

    private fun fmtLatency(m: SideMetrics): String =
        if (m.isError) "ERROR" else "${m.latencyMs}ms"

    private fun fmtFlag(b: Boolean): String = if (b) "✓" else "✗"

    /**
     * Базовый RAG-системпромпт (fair: обе модели получают идентичную инструкцию). Требует источники
     * и цитаты (анти-галлюцинации, день 24) — чтобы CitationDetector имел шанс сработать на ответе.
     */
    private const val DEFAULT_RAG_SYSTEM_PROMPT =
        "You are a helpful assistant answering strictly from the provided [Retrieved context]. " +
            "Do not invent facts. Format: 1) Answer, 2) Sources (list each source › section), " +
            "3) Citations (verbatim fragments in «...» quotes). " +
            "If the context does not contain the answer, say «не знаю»."
}
