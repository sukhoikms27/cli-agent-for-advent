package com.cliagent.llm.token

import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.Usage

class TokenCounter {

    data class SessionTokens(
        var totalPromptTokens: Long = 0,
        var totalCompletionTokens: Long = 0,
        var totalTokens: Long = 0,
        var requestCount: Int = 0,
        var lastRequestTokens: Usage? = null,
        var totalCachedTokens: Long = 0
    )

    private val sessionStats = mutableMapOf<String, SessionTokens>()

    fun recordUsage(sessionId: String, usage: Usage?) {
        if (usage == null) return
        val stats = sessionStats.getOrPut(sessionId) { SessionTokens() }
        stats.totalPromptTokens += usage.promptTokens
        stats.totalCompletionTokens += usage.completionTokens
        stats.totalTokens += usage.totalTokens
        stats.totalCachedTokens += (usage.promptTokensDetails?.cachedTokens ?: 0)
        stats.requestCount++
        stats.lastRequestTokens = usage
    }

    fun getSessionStats(sessionId: String): SessionTokens? =
        sessionStats[sessionId]

    fun estimateMessageTokens(message: ChatMessage): Int {
        // ~4 chars = 1 token for mixed ru/en text
        return (message.content.length / 4) + 4
    }

    fun estimateHistoryTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateMessageTokens(it) }

    fun reset(sessionId: String) {
        sessionStats.remove(sessionId)
    }
}

/**
 * Оценка числа токенов в произвольной строке (без overhead-константы сообщения) — для
 * межартефактных передач и усечения. Та же эвристика ~4 символа/токен, что в [TokenCounter].
 */
fun estimateTokens(text: String): Int = text.length / 4

/**
 * Обрезать строку до <= [maxTokens] (по оценке ~4 символа/токен), добавив [marker] при усечении.
 * Если оценка уже в пределах лимита — возвращается без изменений.
 *
 * Используется для bounded-контекста при межстадийных передачах артефактов (мера C),
 * см. [ArtifactLimits].
 */
fun truncateToTokens(text: String, maxTokens: Int, marker: String = "\n…[усечено]…"): String {
    if (text.isEmpty() || maxTokens <= 0) return if (maxTokens <= 0) marker else text
    if (estimateTokens(text) <= maxTokens) return text
    val charLimit = maxTokens * 4
    return text.take(charLimit) + marker
}

/**
 * Единый источник лимитов межстадийных передач артефактов (мера C) в токенах. Заменяет
 * разбросанные символьные `MAX_CHARS` (4000/6000) в stage-агентах. Оценка ~4 символа/токен.
 *
 * Лимитируются **входные** передачи (артефакт кормит следующую стадию); сам создаваемый
 * артефакт хранится в [com.cliagent.state.TaskState] целиком.
 */
object ArtifactLimits {
    const val PLAN_TOKENS = 2_000              // approvedPlan → execution/validation
    const val IMPLEMENTATION_TOKENS = 4_000    // implementation → validation/done
    const val VERDICT_TOKENS = 2_000           // verdict → done
    const val REQUIREMENTS_TOKENS = 2_000      // requirements → planning
    const val FEEDBACK_TOKENS = 1_500          // feedback → любая стадия
    const val DONE_SUMMARY_INPUT = 3_000       // каждый артефакт в done
    const val DONE_STEPS_TOTAL_TOKENS = 2_000  // суммарно по всем doneSteps в StepAgent
    const val PLAN_IN_STEP_TOKENS = 2_000      // полный план в контексте шага
    const val HISTORY_STAGE_MSG_TOKENS = 1_000 // copy stage-сообщения в history (мера D1)
}
