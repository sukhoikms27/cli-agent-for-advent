package com.cliagent.llm.token

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenCounterTruncationTest {

    @Test
    fun `truncated with marker when over limit`() {
        val marker = "\n…[усечено]…"
        val text = "x".repeat(10_000)   // 2500 токенов
        val out = truncateToTokens(text, maxTokens = 100)
        assertTrue(out.endsWith("…[усечено]…"), "должен добавить маркер: $out")
        // charLimit = 100*4 = 400; + marker
        assertTrue(out.length <= 400 + marker.length)
    }

    @Test
    fun `unchanged when within token limit`() {
        val text = "короткий текст"   // 14 символов → 3 токена
        assertEquals(text, truncateToTokens(text, maxTokens = 100))
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", truncateToTokens("", maxTokens = 100))
    }

    @Test
    fun `non-positive limit returns marker for non-empty text`() {
        assertEquals("\n…[усечено]…", truncateToTokens("text", maxTokens = 0))
    }

    @Test
    fun `custom marker is respected`() {
        val out = truncateToTokens("x".repeat(10_000), maxTokens = 10, marker = "[CUT]")
        assertTrue(out.endsWith("[CUT]"))
    }

    @Test
    fun `estimateTokens is length-div-4`() {
        assertEquals(25, estimateTokens("x".repeat(100)))
        assertEquals(0, estimateTokens(""))
    }

    @Test
    fun `ArtifactLimits constants are positive and ordered`() {
        assertTrue(ArtifactLimits.PLAN_TOKENS > 0)
        assertTrue(ArtifactLimits.IMPLEMENTATION_TOKENS >= ArtifactLimits.PLAN_TOKENS)
        assertTrue(ArtifactLimits.HISTORY_STAGE_MSG_TOKENS > 0)
        assertFalse(ArtifactLimits.HISTORY_STAGE_MSG_TOKENS >= ArtifactLimits.IMPLEMENTATION_TOKENS)
    }
}
