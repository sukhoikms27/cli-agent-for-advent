package com.cliagent.llm.pricing

import com.cliagent.llm.model.Usage

/**
 * Расчёт стоимости по моделям.
 * [ANDROID-DIFF] Аналог Pricing.kt в Android.
 */
object Pricing {
    data class Price(val input: Double, val output: Double)  // $ за 1M токенов

    private val prices = mapOf(
        "glm-5.1"     to Price(input = 20.0, output = 20.0),
        "glm-5"       to Price(input = 20.0, output = 20.0),
        "glm-5-turbo" to Price(input = 5.0, output = 5.0),
        "glm-4.7"     to Price(input = 5.0, output = 5.0),
        "glm-4.5-air" to Price(input = 1.0, output = 1.0),
        // День 26: локальная Ollama (Qwen3) — бесплатно (без API-биллинга). Price(0,0) чтобы
        // /cost показывал $0.00 вместо «No pricing data». Prefix-match: "qwen3:14b" → "qwen3".
        "qwen3"       to Price(input = 0.0, output = 0.0),
        "qwen2.5"     to Price(input = 0.0, output = 0.0),
    )

    fun calculateCost(modelId: String, usage: Usage?): Double? {
        if (usage == null) return null
        val price = priceFor(modelId) ?: return null
        val inputCost = (usage.promptTokens / 1_000_000.0) * price.input
        val outputCost = (usage.completionTokens / 1_000_000.0) * price.output
        return inputCost + outputCost
    }

    fun getPrice(modelId: String): Price? = priceFor(modelId)

    /**
     * День 26: lookup с prefix-match (как [com.cliagent.llm.ModelLimitsRegistry.forModel]).
     *
     * Раньше точечный `prices[modelId]` не находил "qwen3:14b" (Ollama тег с квантизацией) →
     * `/cost` показывал «No pricing data» для локальных моделей. Теперь точное совпадение优先,
     * затем самая длинная запись-ключ, являющаяся prefix'ом: "qwen3:14b" → "qwen3".
     */
    private fun priceFor(modelId: String): Price? {
        val lower = modelId.trim().lowercase()
        prices[lower]?.let { return it }
        return prices.keys
            .filter { lower.startsWith(it) }
            .maxByOrNull { it.length }
            ?.let { prices[it] }
    }
}
