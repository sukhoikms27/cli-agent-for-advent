package com.cliagent.cli

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.OllamaBenchClient
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.SystemPrompts
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
        /**
         * День 31: tokens/sec (throughput generation). Снимается [OllamaBenchClient.measureTokensPerSec]
         * только для local (через benchClient); cloud=null (черезput cloud-провайдеров зависит от сети,
         * не сопоставим с локальной генерацией). null если benchClient=null или замер не удался.
         */
        val tokensPerSec: Double? = null,
        /**
         * День 31: VRAM-использование модели в MB. Снимается ОДИН раз перед циклом вопросов
         * ([OllamaBenchClient.snapshotVram]); одинаковое для всех local-вопросов (модель загружена).
         * null для cloud или если benchClient=null/замер не удался.
         */
        val vramMb: Long? = null,
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
        systemPrompt: String = SystemPrompts.localRagCompare.content,
        /**
         * День 31: Ollama benchmarking-клиент для метрик tokens/sec и VRAM. null = backward-compat
         * (без метрик; поведение дней 28–30). Передаётся из [com.cliagent.cli.ChatCommand.handleCompareLocal]
         * только при resolvedProvider==OLLAMA (native base).
         */
        benchClient: OllamaBenchClient? = null,
    ): List<CompareResult> {
        if (cloudClient == null) {
            AppTerminal.warn("Cloud недоступен (нет API key) — compare в local-only режиме.")
        } else {
            AppTerminal.println("📊 Local vs Cloud RAG compare: ${questions.size} questions × 2 models")
            AppTerminal.println("   local: $localModel  |  cloud: $cloudModel")
        }

        // День 31: VRAM-снапшот ОДИН раз перед циклом (модель уже загружена; не меняется между вопросами).
        // null если benchClient=null или замер не удался — метрика просто отсутствует в отчёте.
        val vramMb = try {
            benchClient?.snapshotVram()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
        if (vramMb != null) {
            AppTerminal.println("   local VRAM: ${vramMb}MB (snapshot /api/ps)")
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
            // День 31: tokens/sec замер через benchClient (если есть) — отдельный короткий запрос, чтобы
            // не зависеть от streaming-латентности основного прогона (честная throughput-метрика).
            val localTps = try {
                benchClient?.measureTokensPerSec(localModel, systemPrompt, prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            val localMetrics = measure(
                localClient, localModel, prompt, q, hits, maxTokens = 2048,
                tokensPerSec = localTps, vramMb = vramMb,
            )
            AppTerminal.println(
                "  local: ${fmtLatency(localMetrics)}  kw ${localMetrics.keywordHits}/${localMetrics.keywordTotal}  " +
                    "src ${fmtFlag(localMetrics.sourcesPresent)}  cite ${fmtFlag(localMetrics.citationsPresent)}  " +
                    "len ${localMetrics.length}" + (if (localTps != null) "  tps ${fmtTps(localTps)}" else "")
            )

            val cloudMetrics = if (cloudClient != null) {
                // Cloud может позволить больше токенов (или null — provider default). VRAM/tps не применимы
                // к cloud (черезput зависит от сети, не сопоставим с локальной генерацией).
                measure(cloudClient, cloudModel ?: "cloud", prompt, q, hits, maxTokens = null,
                    tokensPerSec = null, vramMb = null).also { cm ->
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
        /** День 31: throughput/VRAM метрики (только для local через benchClient; null для cloud). */
        tokensPerSec: Double? = null,
        vramMb: Long? = null,
    ): SideMetrics {
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "system", content = SystemPrompts.localRagCompare.content),
                ChatMessage(role = "user", content = prompt)),
            temperature = 0.0,
            maxTokens = maxTokens,
            // День 28 (fix): стриминг под капотом measure. qwen3 thinking на RAG-контексте может
            // думать >120с → non-streaming socketTimeout срабатывает → retry-loop (10 попыток)
            // → compare-local 3 висит 20+ минут. При stream:true socketTimeout измеряет время
            // МЕЖДУ байтами (reasoning-токены идут постоянно) → не срабатывает. Метрики (latency,
            // content, usage) те же — дельты склеиваются в полную строку.
            stream = true,
            streamOptions = com.cliagent.llm.model.StreamOptions(includeUsage = true),
        )
        val start = System.currentTimeMillis()
        val fullContent = StringBuilder()
        var finalUsage: com.cliagent.llm.model.Usage? = null
        var finishReason: String? = null
        var errorMsg: String? = null
        try {
            client.chatStream(request).collect { chunk ->
                when (chunk) {
                    is com.cliagent.llm.model.StreamChunk.Delta -> fullContent.append(chunk.content)
                    is com.cliagent.llm.model.StreamChunk.Reasoning -> { /* thinking — не входит в answer */ }
                    is com.cliagent.llm.model.StreamChunk.Done -> {
                        finalUsage = chunk.usage
                        finishReason = chunk.finishReason
                    }
                    is com.cliagent.llm.model.StreamChunk.Error -> {
                        errorMsg = if (chunk.message.isBlank()) "LLM error (code ${chunk.code})" else chunk.message
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            return errorMetrics("ERROR: ${e.message}", elapsed, q, hits, tokensPerSec, vramMb)
        }
        val elapsed = System.currentTimeMillis() - start
        return if (errorMsg != null) {
            errorMetrics("ERROR: $errorMsg", elapsed, q, hits, tokensPerSec, vramMb)
        } else {
            val text = fullContent.toString().ifBlank { "(empty)" }
            successMetrics(text, elapsed, finalUsage?.promptTokens ?: 0, finalUsage?.completionTokens ?: 0, q, hits, tokensPerSec, vramMb)
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
        tokensPerSec: Double? = null,
        vramMb: Long? = null,
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
            tokensPerSec = tokensPerSec,
            vramMb = vramMb,
        )
    }

    /** Метрики для ошибочного ответа (throwable / LlmResult.Error). tokensPerSec/vramMb сохраняются
     *  даже при ошибке генерации — они о модели/окружении, не о конкретном ответе (день 31). */
    private fun errorMetrics(
        answer: String,
        latencyMs: Long,
        q: CompareQuestion,
        hits: List<ScoredChunk>?,
        tokensPerSec: Double? = null,
        vramMb: Long? = null,
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
        tokensPerSec = tokensPerSec,
        vramMb = vramMb,
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
                    // День 31: добавлены L tps / L vram (cloud-аналогов нет — черезput cloud зависит от сети).
                    row("Q", "L ms", "L kw", "L src", "L tps", "C ms", "C kw", "C src", "Δkw")
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
                            l.tokensPerSec?.let { fmtTps(it) } ?: "-",
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
                    row("Q", "L ms", "L kw", "L src", "L cite", "L len", "L tps")
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
                            l.tokensPerSec?.let { fmtTps(it) } ?: "-",
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
        // День 31: VRAM (одинаков для всех local-вопросов — снапшот до цикла) и avg tokens/sec.
        val vram = results.firstOrNull()?.local?.vramMb
        if (vram != null) AppTerminal.println("LOCAL VRAM: ${vram}MB (snapshot /api/ps)")
        val tpsValues = results.mapNotNull { it.local.tokensPerSec }
        if (tpsValues.isNotEmpty()) {
            AppTerminal.println("LOCAL avg throughput: ${fmtTps(tpsValues.average())} tok/s (over ${tpsValues.size} measurement(s))")
        }
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

    /** День 31: tokens/sec в compact-формате (1 decimal). null → "-" (метрика отсутствует). */
    private fun fmtTps(tps: Double): String = String.format("%.1f", tps)
}
