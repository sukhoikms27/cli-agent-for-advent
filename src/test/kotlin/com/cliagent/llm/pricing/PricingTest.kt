package com.cliagent.llm.pricing

import com.cliagent.llm.model.Usage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * День 26: unit-тесты [Pricing] — добавлен qwen3 (локальная Ollama, Price(0,0)) + prefix-match lookup.
 * Раньше `prices[modelId]` не находил "qwen3:14b" (Ollama тег) → /cost показывал «No pricing data».
 * Теперь prefix-match: "qwen3:14b" → "qwen3".
 */
class PricingTest {

    @Test
    fun `qwen3 base model resolves to free price`() {
        val price = Pricing.getPrice("qwen3")
        assertEquals(0.0, price?.input)
        assertEquals(0.0, price?.output)
    }

    @Test
    fun `qwen3 ollama tag with quantization prefix-matches to free price`() {
        // "qwen3:14b" → prefix "qwen3" → Price(0,0). Раньше — null (неточный match).
        val price = Pricing.getPrice("qwen3:14b")
        assertEquals(0.0, price?.input)
        assertEquals(0.0, price?.output)
    }

    @Test
    fun `qwen2_5 prefix-matches to free price`() {
        val price = Pricing.getPrice("qwen2.5:32b-instruct-q5_K_M")
        assertEquals(0.0, price?.input)
        assertEquals(0.0, price?.output)
    }

    @Test
    fun `calculateCost for qwen3 returns 0_0 (local = free)`() {
        val usage = Usage(promptTokens = 1500, completionTokens = 500, totalTokens = 2000)
        val cost = Pricing.calculateCost("qwen3:14b", usage)
        assertEquals(0.0, cost)
    }

    @Test
    fun `calculateCost returns null for usage null`() {
        assertNull(Pricing.calculateCost("qwen3", null))
    }

    @Test
    fun `glm models still have non-zero pricing (no regression)`() {
        val glm = Pricing.getPrice("glm-5.1")!!
        assertEquals(20.0, glm.input)
        assertEquals(20.0, glm.output)
        val turbo = Pricing.getPrice("glm-5-turbo")!!
        assertEquals(5.0, turbo.input)
    }

    @Test
    fun `glm-5 prefix-matches glm-5_1 variant without losing turbo`() {
        // "glm-5-turbo" должен резолвиться в свой собственный entry (5.0), а не prefix-match в glm-5 (20.0):
        // самый длинный matching prefix wins → "glm-5-turbo" (exact) > "glm-5" (prefix).
        val turbo = Pricing.getPrice("glm-5-turbo")!!
        assertEquals(5.0, turbo.input)
        assertEquals(5.0, turbo.output)
    }

    @Test
    fun `calculateCost for glm-5_1 computes non-zero from usage`() {
        val usage = Usage(promptTokens = 1_000_000, completionTokens = 1_000_000, totalTokens = 2_000_000)
        // 1M input × $20 + 1M output × $20 = $40
        val cost = Pricing.calculateCost("glm-5.1", usage)
        assertEquals(40.0, cost!!)
    }

    @Test
    fun `unknown model returns null price`() {
        assertNull(Pricing.getPrice("some-unknown-model-xyz"))
    }
}
