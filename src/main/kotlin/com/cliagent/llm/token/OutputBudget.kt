package com.cliagent.llm.token

/**
 * Бюджет `max_tokens` на ответ (мера A против обрыва ответов на стадиях).
 *
 * `max_tokens` нигде не задавался → ответ резался серверным дефолтом провайдера (z.ai). Здесь
 * budget вычисляется от фактической оценки prompt: `contextLimit - estimatedPrompt - SAFETY_MARGIN`,
 * ограниченный сверху серверным максимумом output, снизу — минимальным полом (гарантия места под
 * completion даже при переполненном контексте).
 *
 * Консервативный [MODEL_CONTEXT_LIMIT] (200K) безопаснее реального потолка GLM-5.x (до 1M):
 * никогда не превысит реальный лимит context window. Оценка prompt грубая (~4 символа/токен),
 * поэтому [SAFETY_MARGIN] + детекция `finish_reason=length` (мера B) — подстраховка.
 */
object OutputBudget {
    const val MODEL_CONTEXT_LIMIT = 200_000      // консервативный лимит context window GLM-5.1
    const val MODEL_MAX_OUTPUT = 128_000          // серверный максимум output GLM-5.1/5.2
    const val MIN_RESERVED_OUTPUT = 4_096         // нижний пол: даже при переполненном контексте
    const val SAFETY_MARGIN = 2_000               // резерв на overhead, не учтённый в estimate

    /**
     * `max_tokens` для запроса: остаток context window под completion, ограниченный потолком и полом.
     * Для обычного stage-промпта (~2-8K токенов) даёт ~190K — далеко за потребностями стадии,
     * нормальные ответы не режутся; патологию (сильно раздутый контекст) ловит мера B.
     */
    fun maxTokensFor(estimatedPromptTokens: Int): Int {
        val reserved = MODEL_CONTEXT_LIMIT - estimatedPromptTokens - SAFETY_MARGIN
        return reserved.coerceIn(MIN_RESERVED_OUTPUT, MODEL_MAX_OUTPUT)
    }
}
