package com.cliagent.llm.token

import com.cliagent.llm.ModelLimitsRegistry

/**
 * Бюджет `max_tokens` на ответ (мера A против обрыва ответов на стадиях).
 *
 * `max_tokens` нигде не задавался → ответ резался серверным дефолтом провайдера (z.ai). Здесь
 * budget вычисляется от фактической оценки prompt: `contextLimit - estimatedPrompt - SAFETY_MARGIN`,
 * ограниченный сверху серверным максимумом output, снизу — минимальным полом (гарантия места под
 * completion даже при переполненном контексте).
 *
 * День 25: пер-модельные лимиты через [ModelLimitsRegistry]. Главная точка входа — перегрузка
 * [maxTokensFor] с `modelId`: для `qwen2.5` это 128K контекст / 8K output, для `glm-5.1` —
 * 200K / 128K. Старая перегрузка [maxTokensFor] без `modelId` оставлена для backward-compat
 * (делегирует в GLM-константы [MODEL_CONTEXT_LIMIT] / [MODEL_MAX_OUTPUT]) — @Deprecated WARNING,
 * но не удаляется (schema evolution).
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
     * День 25: `max_tokens` для запроса с пер-модельными лимитами. Лимиты берутся из
     * [ModelLimitsRegistry.forModel] (prefix-match по modelId: "qwen2.5:32b..." → qwen2.5),
     * остаток context window под completion ограничивается потолком модели снизу [MIN_RESERVED_OUTPUT].
     */
    fun maxTokensFor(modelId: String, estimatedPromptTokens: Int): Int {
        val limits = ModelLimitsRegistry.forModel(modelId)
        val reserved = limits.contextWindow - estimatedPromptTokens - SAFETY_MARGIN
        return reserved.coerceIn(MIN_RESERVED_OUTPUT, limits.maxOutput)
    }

    /**
     * `max_tokens` для запроса: остаток context window под completion, ограниченный потолком и полом.
     * Для обычного stage-промпта (~2-8K токенов) даёт ~190K — далеко за потребностями стадии,
     * нормальные ответы не режутся; патологию (сильно раздутый контекст) ловит мера B.
     *
     * День 25: deprecated — использует хардкод GLM-5.1 констант, некорректно для локальных моделей.
     * Используйте перегрузку с `modelId` для пер-модельных лимитов.
     */
    @Deprecated(
        "Use overload with modelId for per-model limits",
        ReplaceWith("maxTokensFor(\"glm-5.1\", estimatedPromptTokens)"),
    )
    fun maxTokensFor(estimatedPromptTokens: Int): Int {
        val reserved = MODEL_CONTEXT_LIMIT - estimatedPromptTokens - SAFETY_MARGIN
        return reserved.coerceIn(MIN_RESERVED_OUTPUT, MODEL_MAX_OUTPUT)
    }
}
