package com.cliagent.llm.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
