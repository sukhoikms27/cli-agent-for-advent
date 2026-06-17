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
        "glm-4.5-air" to Price(input = 1.0, output = 1.0)
    )

    fun calculateCost(modelId: String, usage: Usage?): Double? {
        if (usage == null) return null
        val price = prices[modelId] ?: return null
        val inputCost = (usage.promptTokens / 1_000_000.0) * price.input
        val outputCost = (usage.completionTokens / 1_000_000.0) * price.output
        return inputCost + outputCost
    }

    fun getPrice(modelId: String): Price? = prices[modelId]
}
