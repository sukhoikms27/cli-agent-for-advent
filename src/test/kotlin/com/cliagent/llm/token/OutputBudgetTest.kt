package com.cliagent.llm.token

import com.cliagent.llm.ModelLimitsRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION") // старая перегрузка maxTokensFor(Int) — deprecated день 25, но deliberately тестируется для backward-compat
class OutputBudgetTest {

    @Test
    fun `small prompt yields large budget capped at MODEL_MAX_OUTPUT`() {
        // 0 prompt → 200000 - 0 - 2000 = 198000, но capped до MODEL_MAX_OUTPUT (128000)
        assertEquals(OutputBudget.MODEL_MAX_OUTPUT, OutputBudget.maxTokensFor(0))
    }

    @Test
    fun `budget within bounds for normal prompt`() {
        val budget = OutputBudget.maxTokensFor(8_000)
        assertTrue(budget >= OutputBudget.MIN_RESERVED_OUTPUT)
        assertTrue(budget <= OutputBudget.MODEL_MAX_OUTPUT)
        // 200000 - 8000 - 2000 = 190000 → capped до 128000
        assertEquals(OutputBudget.MODEL_MAX_OUTPUT, budget)
    }

    @Test
    fun `huge prompt clamps to MIN_RESERVED_OUTPUT`() {
        // prompt больше context window → остаток отрицательный → clamp снизу
        assertEquals(OutputBudget.MIN_RESERVED_OUTPUT, OutputBudget.maxTokensFor(500_000))
    }

    @Test
    fun `budget decreases as prompt grows near the limit`() {
        val small = OutputBudget.maxTokensFor(150_000)   // 200000-150000-2000=48000
        val large = OutputBudget.maxTokensFor(195_000)   // 200000-195000-2000=3000 → clamp 4096
        assertTrue(small > large)
        assertEquals(OutputBudget.MIN_RESERVED_OUTPUT, large)
    }

    // ── День 25: пер-модельные лимиты (новая перегрузка maxTokensFor(modelId, Int)) ──

    @Test
    fun `qwen2_5 with small prompt yields maxOutput of model`() {
        // 0 prompt → 128000 - 0 - 2000 = 126000 → capped до maxOutput qwen2.5 (8192)
        assertEquals(ModelLimitsRegistry.forModel("qwen2.5").maxOutput, OutputBudget.maxTokensFor("qwen2.5", 0))
        assertEquals(8_192, OutputBudget.maxTokensFor("qwen2.5", 0))
    }

    @Test
    fun `ollama colon-suffix model id matches by prefix`() {
        // prefix-match: "qwen2.5:32b-instruct-q5_K_M" → ключ "qwen2.5"
        assertEquals(8_192, OutputBudget.maxTokensFor("qwen2.5:32b-instruct-q5_K_M", 0))
    }

    @Test
    fun `glm-5_1 still yields MODEL_MAX_OUTPUT for backward compat`() {
        assertEquals(OutputBudget.MODEL_MAX_OUTPUT, OutputBudget.maxTokensFor("glm-5.1", 0))
        assertEquals(128_000, OutputBudget.maxTokensFor("glm-5.1", 0))
    }

    @Test
    fun `unknown model with huge prompt clamps to MIN_RESERVED_OUTPUT`() {
        // неизвестная модель → DEFAULT (128K / 8K); 500K prompt → остаток отрицательный → clamp снизу
        assertEquals(OutputBudget.MIN_RESERVED_OUTPUT, OutputBudget.maxTokensFor("unknown-model-xyz", 500_000))
    }

    @Test
    fun `qwen2_5 with overflow prompt clamps to MIN_RESERVED_OUTPUT`() {
        // 128000 - 200000 - 2000 < 0 → clamp снизу до MIN_RESERVED_OUTPUT
        assertEquals(OutputBudget.MIN_RESERVED_OUTPUT, OutputBudget.maxTokensFor("qwen2.5", 200_000))
    }
}
