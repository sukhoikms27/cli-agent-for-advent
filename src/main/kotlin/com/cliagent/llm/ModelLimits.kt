package com.cliagent.llm

/**
 * Лимиты модели: context window + серверный максимум output (в токенах). День 25: заменила
 * хардкод GLM-5.1 констант в [com.cliagent.llm.token.OutputBudget]. Теперь локальные модели
 * (Qwen2.5: 128K контекст, ~8K output) получают корректный бюджет `max_tokens`, а не
 * GLM-специфичные 200K/128K.
 *
 * @param contextWindow максимальный суммарный размер (prompt + completion) в токенах
 * @param maxOutput серверный/модельный потолок output-токенов на один ответ
 */
data class ModelLimits(
    val contextWindow: Int,
    val maxOutput: Int,
)

/**
 * Реестр лимитов по моделям (prefix-match). День 25: поддерживает z.ai GLM-5.x и локальные
 * модели Ollama (Qwen2.5). [DEFAULT] — консервативный fallback для неизвестных моделей
 * (128K контекст / 8K output), безопасный для большинства локальных моделей.
 */
object ModelLimitsRegistry {
    private val registry = mapOf(
        // z.ai GLM-5.x (cloud)
        "glm-5.1"     to ModelLimits(contextWindow = 200_000, maxOutput = 128_000),
        "glm-5"       to ModelLimits(contextWindow = 200_000, maxOutput = 128_000),
        "glm-5-turbo" to ModelLimits(contextWindow = 200_000, maxOutput = 128_000),
        "glm-4.7"     to ModelLimits(contextWindow = 128_000, maxOutput = 8_192),
        "glm-4.5-air" to ModelLimits(contextWindow = 128_000, maxOutput = 8_192),
        // Ollama-локальные (Qwen2.5): 128K контекст, ~8K output на M3 Pro
        "qwen2.5"     to ModelLimits(contextWindow = 128_000, maxOutput = 8_192),
        "qwen3"       to ModelLimits(contextWindow = 128_000, maxOutput = 8_192),
        "llama3.1"    to ModelLimits(contextWindow = 128_000, maxOutput = 8_192),
        "mistral-nemo" to ModelLimits(contextWindow = 128_000, maxOutput = 8_192),
    )

    /** Консервативный default для неизвестных моделей (безопасный для локальных). */
    val DEFAULT = ModelLimits(contextWindow = 128_000, maxOutput = 8_192)

    /**
     * Лимиты по modelId. Prefix-match без учёта регистра: "qwen2.5:32b-instruct-q5_K_M" →
     * "qwen2.5"; "glm-5.1" → "glm-5.1". Неизвестная → [DEFAULT].
     */
    fun forModel(modelId: String): ModelLimits {
        val lower = modelId.trim().lowercase()
        // Точное совпадение优先, затем prefix (glm-5-turbo vs glm-5).
        registry[lower]?.let { return it }
        // Prefix-match: самая длинная запись-ключ, являющаяся prefix'ом modelId.
        val prefixMatch = registry.keys
            .filter { lower.startsWith(it) }
            .maxByOrNull { it.length }
        return prefixMatch?.let { registry[it] } ?: DEFAULT
    }
}
