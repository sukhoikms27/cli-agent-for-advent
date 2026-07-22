package com.cliagent.review

import com.cliagent.llm.LlmCallException
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatRequest
import com.cliagent.review.gh.DiffLine
import com.cliagent.review.gh.GhCli
import com.cliagent.review.gh.GhException
import com.cliagent.review.gh.PendingReviewPoster
import com.cliagent.review.gh.PrCoordinates
import com.cliagent.review.gh.PrFiles
import com.cliagent.review.gh.PrUrlParser
import com.cliagent.review.gh.ReviewBodyDto
import com.cliagent.review.gh.ReviewCommentDto
import com.cliagent.review.review.ChecklistParser
import com.cliagent.review.review.ReviewPrompt
import com.cliagent.review.review.ReviewResult
import com.cliagent.review.review.ResponseParser
import com.cliagent.review.review.VerdictProposal
import com.cliagent.rag.RagRetriever
import com.cliagent.rag.ScoredChunk

/**
 * Оркестратор ревью: parse URL → load diff → RAG → LLM → anchor → print CRM-репорт → post pending review.
 *
 * @property factory wiring (LLM client, embedder, retriever).
 */
class ReviewPipeline(private val factory: ReviewBotFactory) {

    /**
     * Запуск ревью.
     *
     * @param prUrl URL PR (https://github.com/owner/repo/pull/N).
     * @param sprint номер спринта.
     * @param studentName имя студента.
     * @param publish если true — сразу публикует review (REQUEST_CHANGES / APPROVE). Dangerous.
     *                Если false (default) — создаёт PENDING review.
     * @param noRag если true — пропустить RAG-retrieval.
     * @return URL созданного review или null, если не создан (ошибка/нет комментариев).
     */
    suspend fun run(
        prUrl: String,
        sprint: Int,
        studentName: String,
        publish: Boolean = false,
        noRag: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): String? {
        onProgress("Проверяю gh auth…")
        val account = GhCli.requireAuth()
        onProgress("gh авторизован как: $account")

        // 1. Парсинг URL.
        val coords = PrUrlParser.parse(prUrl)
            ?: throw GhException("Не удалось распарсить PR URL: $prUrl")

        // 2. Загрузка diff + индексация.
        onProgress("Загружаю файлы PR #${coords.number}…")
        val diffData = PrFiles.load(coords)
            ?: throw GhException("PR пустой или недоступен: ${coords.htmlUrl}")
        if (diffData.truncated) {
            onProgress("⚠️ Diff обрезан до ${PrFiles.MAX_DIFF_CHARS} символов (контекстное окно).")
        }
        onProgress("Файлов: ${diffData.fileNames.size}, добавленных строк: ${diffData.lines.size}")

        // 3. Чеклист + RAG-контекст.
        val checklistItems = loadChecklist()
        val ragContext = if (!noRag && factory.ragRetriever != null) {
            retrieveRag(diffData.rawDiff, sprint, factory.ragRetriever, onProgress)
        } else null

        // 4. LLM review.
        onProgress("Запускаю LLM review (model=${factory.model})…")
        val prompt = ReviewPrompt.build(
            diff = diffData.rawDiff,
            checklistItems = checklistItems,
            studentName = studentName,
            sprint = sprint,
            ragContext = ragContext,
            fileNames = diffData.fileNames,
        )
        val rawResponse = callLlm(prompt, onProgress)

        // 5. Парсинг ответа.
        val review = ResponseParser.parse(rawResponse, studentName, checklistItems)
        onProgress(
            "Ревью готово: verdict=${review.verdict}, " +
                "${review.checklist.count { !it.passed }} ❌, " +
                "${review.lineComments.size} line-comments"
        )

        // 6. Анкоринг line-comments к diff-valid строкам.
        val anchored = anchorComments(review, diffData.lines)
        val unanchored = review.lineComments.size - anchored.size
        if (unanchored > 0) {
            onProgress("⚠️ $unanchored комментариев не привязаны к строкам — попадут в body summary.")
        }

        // 7. Печать CRM-репорта в терминал.
        printCrmReport(review)

        // 8. Создание pending review (или publish).
        return postReview(coords, review, anchored, publish, onProgress)
    }

    /** Загружает embedded чеклист (sprint-7). */
    private fun loadChecklist(): List<String> {
        val res = javaClass.getResourceAsStream("/checklist-sprint-7.md")
            ?: return emptyList()
        return res.bufferedReader(Charsets.UTF_8).use { ChecklistParser.parse(it.readText()) }
    }

    /** RAG-retrieval (мягкая деградация как в ReviewPrCommand). */
    private suspend fun retrieveRag(
        diff: String,
        sprint: Int,
        retriever: RagRetriever,
        onProgress: (String) -> Unit,
    ): String? {
        return try {
            onProgress("RAG-retrieval (sprint=$sprint)…")
            val query = "требования чеклиста спринта $sprint: меню архивов, заметок, обработка ввода, " +
                "навигация, разделение по файлам"
            val hits: List<ScoredChunk>? = retriever.retrieve(query)
            if (hits.isNullOrEmpty()) null
            else hits.joinToString("\n\n") { c ->
                "### ${c.chunk.title}\n${c.chunk.text}"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            onProgress("⚠️ RAG недоступен (${e.message?.take(80)}). Ревью без контекста.")
            null
        }
    }

    /** LLM-вызов с retry на упрощённом промпте. */
    private suspend fun callLlm(
        messages: List<com.cliagent.llm.model.ChatMessage>,
        onProgress: (String) -> Unit,
    ): String {
        val request = ChatRequest(model = factory.model, messages = messages, temperature = 0.2)
        return try {
            when (val r = factory.client.chat(request)) {
                is LlmResult.Success -> r.data.choices.first().message.content
                is LlmResult.Error -> throw LlmCallException(r.code, "LLM error: ${r.message}")
            }
        } catch (e: LlmCallException) {
            onProgress("⚠️ LLM-ошибка, retry без RAG-context: ${e.message.take(80)}")
            val simplified = messages.map { it.copy(content = it.content.take(4000)) }
            val req2 = ChatRequest(model = factory.model, messages = simplified, temperature = 0.2)
            when (val r2 = factory.client.chat(req2)) {
                is LlmResult.Success -> r2.data.choices.first().message.content
                is LlmResult.Error -> throw LlmCallException(
                    r2.code, "LLM error: ${r2.message} (retry failed)",
                )
            }
        }
    }

    /**
     * Анкорит line-comments к diff-valid строкам:
     *  - если LLM дала валидный `line` и файл присутствует в diff — используем как есть,
     *  - иначе ищем через [DiffLineIndexer.findLine] по anchorHint или по тексту из body,
     *  - если не вышло — comment идёт в summary (не привязан к строке).
     */
    private fun anchorComments(
        review: ReviewResult,
        diffLines: List<DiffLine>,
    ): List<ReviewCommentDto> {
        val out = mutableListOf<ReviewCommentDto>()
        val byFile = diffLines.groupBy { it.path }
        for (c in review.lineComments) {
            val candidates = byFile[c.file] ?: continue
            val target = when {
                c.line > 0 && candidates.any { it.line == c.line } ->
                    candidates.first { it.line == c.line }
                !c.anchorHint.isNullOrBlank() ->
                    findInLines(candidates, c.anchorHint) ?: findInLines(candidates, c.body)
                else -> findInLines(candidates, c.body) ?: candidates.firstOrNull()
            }
            if (target != null) {
                out.add(
                    ReviewCommentDto(
                        path = c.file,
                        line = target.line,
                        side = "RIGHT",
                        body = c.body,
                    )
                )
            }
            // иначе — пропускаем (попадёт в summary через review.summary/criticalRemarks)
        }
        return out
    }

    /** Ищет первую added-line, чей content содержит любой из ключей body (первые ~30 символов, lower). */
    private fun findInLines(lines: List<DiffLine>, hint: String): DiffLine? {
        val keys = hint.lowercase()
            .split(Regex("""[\s,.;:()]+"""))
            .filter { it.length >= 4 }
            .take(3)
        if (keys.isEmpty()) return null
        return lines.firstOrNull { line ->
            val low = line.content.lowercase()
            keys.any { k -> low.contains(k) }
        }
    }

    /** Печать CRM-репорта в авторском формате. */
    private fun printCrmReport(review: ReviewResult) {
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=".repeat(60))
        sb.appendLine("Привет, ${review.studentName}!")
        sb.appendLine()
        sb.appendLine("1. Выполнение требований задания:")
        sb.appendLine()
        review.checklist.forEach { item ->
            val mark = if (item.passed) "✅" else "❌"
            val ev = item.evidence?.let { " ($it)" } ?: ""
            sb.appendLine("   $mark ${item.text}$ev")
        }
        sb.appendLine()
        sb.appendLine("2. ⚠️ Критические замечания:")
        if (review.criticalRemarks.isEmpty()) {
            sb.appendLine("   Таких нет, ты молодец! Работа принята!")
        } else {
            review.criticalRemarks.forEach { sb.appendLine("   - $it") }
        }
        sb.appendLine()
        sb.appendLine("3. 🍏 Рекомендации, не влияющие на результат проверки работы:")
        if (review.recommendations.isEmpty()) {
            sb.appendLine("   Хорошая работа, рекомендаций нет!")
        } else {
            review.recommendations.forEach { sb.appendLine("   - $it") }
        }
        sb.appendLine("=".repeat(60))
        println(sb.toString())
    }

    /** Создание pending review (или publish) через gh. */
    private suspend fun postReview(
        coords: PrCoordinates,
        review: ReviewResult,
        comments: List<ReviewCommentDto>,
        publish: Boolean,
        onProgress: (String) -> Unit,
    ): String? {
        val event: String? = when {
            !publish -> null  // PENDING: GitHub не принимает "PENDING" строкой — нужно опустить event.
            review.verdict == VerdictProposal.ACCEPT -> "APPROVE"
            else -> "REQUEST_CHANGES"
        }
        val bodyText = buildString {
            appendLine(review.summary)
            appendLine()
            if (review.criticalRemarks.isNotEmpty()) {
                appendLine("**Критические замечания:**")
                review.criticalRemarks.forEach { appendLine("- $it") }
                appendLine()
            }
            if (review.recommendations.isNotEmpty()) {
                appendLine("**Рекомендации (не влияют на приём):**")
                review.recommendations.forEach { appendLine("- $it") }
            }
        }
        val dto = ReviewBodyDto(body = bodyText.trimEnd(), event = event, comments = comments)
        val eventLabel = event ?: "PENDING(omitted)"
        onProgress("Создаю ${if (publish) "published" else "pending"} review (${comments.size} comments, event=$eventLabel)…")
        val url = PendingReviewPoster.post(coords, dto)
        if (url != null) {
            val status = if (publish) "опубликовано" else "pending (виден только тебе)"
            println()
            println("✓ Review $status: $url")
            if (!publish) {
                println("  Открой PR в GitHub, доработай комментарии и нажми «Submit review»:")
                println("  ${coords.htmlUrl}/files")
            }
        } else {
            println("⚠️ Review создан, но URL не извлечён из ответа. Проверь: ${coords.htmlUrl}")
        }
        return url
    }
}
