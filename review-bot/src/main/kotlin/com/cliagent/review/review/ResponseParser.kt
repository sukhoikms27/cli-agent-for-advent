package com.cliagent.review.review

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Парсит JSON-ответ LLM в [ReviewResult].
 *
 * LLM может вернуть:
 *  - чистый JSON (best case) → парсим напрямую через kotlinx.serialization.
 *  - JSON, обёрнутый в ```` ```json ... ```` (markdown-блок) → вырезаем обёртку.
 *  - JSON + рандомный текст до/после → извлекаем первый `{ ... }` блок.
 *  - не JSON вообще → fallback: пустой результат с verdict=REJECT и пометкой в criticalRemarks.
 *
 * @property studentName подставляется в [ReviewResult] (LLM его не возвращает — оно из CLI).
 */
object ResponseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Serializable
    private data class ChecklistItemDto(
        val text: String = "",
        val passed: Boolean = false,
        val evidence: String? = null,
    )

    @Serializable
    private data class LineCommentDto(
        val file: String = "",
        val line: Int = 0,
        val side: String = "RIGHT",
        val body: String = "",
        val anchorHint: String? = null,
    )

    @Serializable
    private data class ReviewResultDto(
        val checklist: List<ChecklistItemDto> = emptyList(),
        val criticalRemarks: List<String> = emptyList(),
        val recommendations: List<String> = emptyList(),
        val lineComments: List<LineCommentDto> = emptyList(),
        val verdict: String = "REJECT",
    )

    /**
     * @param raw ответ LLM (может быть с markdown-обёрткой или мусором).
     * @param studentName имя студента (из CLI, не из LLM).
     * @param fallbackChecklist пункты чеклиста из embedded resource (если LLM не вернул checklist).
     * @return [ReviewResult]. Никогда не бросает — fallback на пустой результат при ошибке парсинга.
     */
    fun parse(
        raw: String,
        studentName: String,
        fallbackChecklist: List<String>,
    ): ReviewResult {
        val cleaned = extractJson(raw) ?: return fallback(raw, studentName, fallbackChecklist)
        val dto = try {
            json.decodeFromString(ReviewResultDto.serializer(), cleaned)
        } catch (e: Throwable) {
            return fallback(raw, studentName, fallbackChecklist)
        }
        val checklist = dto.checklist.map {
            ChecklistItem(text = it.text, passed = it.passed, evidence = it.evidence)
        }.ifEmpty {
            // LLM не вернул чеклист — используем fallback с passed=false (всё «непроверено»).
            fallbackChecklist.map { ChecklistItem(it, passed = false, evidence = null) }
        }
        val lineComments = dto.lineComments.map { c ->
            ReviewComment(
                file = c.file,
                line = c.line,
                side = CommentSide.valueOf(c.side.uppercase()),
                body = c.body,
                anchorHint = c.anchorHint,
            )
        }
        val verdict = runCatching { VerdictProposal.valueOf(dto.verdict.uppercase()) }
            .getOrDefault(VerdictProposal.REJECT)
        return ReviewResult(
            studentName = studentName,
            checklist = checklist,
            criticalRemarks = dto.criticalRemarks,
            recommendations = dto.recommendations,
            lineComments = lineComments,
            verdict = verdict,
        )
    }

    /**
     * Извлекает JSON-объект из [raw]:
     *  - если есть ```` ```json ... ```` → берём содержимое блока,
     *  - иначе ищем первый сбалансированный `{ ... }`.
     * @return чистый JSON-текст или null.
     */
    internal fun extractJson(raw: String): String? {
        // 1. Markdown-блок ```json ... ```
        val md = Regex("""```(?:json)?\s*(\{[\s\S]*\})\s*```""").find(raw)
        if (md != null) return md.groupValues[1].trim()
        // 2. Первый `{` и парный ему `}` (балансировка с учётом строк).
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var escape = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escape -> escape = false
                c == '\\' && inStr -> escape = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1).trim()
                }
            }
        }
        return null
    }

    /** Fallback при невозможности распарсить JSON — verdict=REJECT, комментарий об ошибке. */
    private fun fallback(
        raw: String,
        studentName: String,
        fallbackChecklist: List<String>,
    ): ReviewResult = ReviewResult(
        studentName = studentName,
        checklist = fallbackChecklist.map { ChecklistItem(it, passed = false, evidence = null) },
        criticalRemarks = listOf(
            "⚠️ Не удалось распарсить ответ LLM как JSON. Ручная проверка требуется. " +
                "Сырой ответ (первые 300 символов): ${raw.take(300)}",
        ),
        recommendations = emptyList(),
        lineComments = emptyList(),
        verdict = VerdictProposal.REJECT,
    )
}
