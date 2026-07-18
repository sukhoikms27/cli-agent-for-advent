package com.cliagent.cli

import com.cliagent.llm.model.SystemPrompts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 32 — unit-тесты чистых функций сборки промпта PR-review ([extractQueryFromDiff],
 * [buildReviewPrompt]). Без IO, без LLM — детерминированные.
 */
class ReviewPromptBuilderTest {

    // ── extractQueryFromDiff ──────────────────────────────────────────────────

    @Test
    fun `extractQuery empty diff returns empty string`() {
        assertEquals("", extractQueryFromDiff(""))
        assertEquals("", extractQueryFromDiff("   \n  "))
    }

    @Test
    fun `extractQuery extracts file basenames from diff headers`() {
        val diff = """
            diff --git a/src/Foo.kt b/src/Foo.kt
            index 123..456 100644
            --- a/src/Foo.kt
            +++ b/src/Foo.kt
            @@ -1,3 +1,4 @@
             line
            +added
        """.trimIndent()
        val q = extractQueryFromDiff(diff)
        assertTrue(q.contains("Foo"), "should extract basename 'Foo' from diff header, got: '$q'")
    }

    @Test
    fun `extractQuery extracts identifiers from added lines`() {
        val diff = """
            +++ b/Main.kt
            @@ -1 +1,2 @@
            +val myImportantFunction = 42
        """.trimIndent()
        val q = extractQueryFromDiff(diff)
        assertTrue(q.contains("myImportantFunction"), "should extract identifier from added line, got: '$q'")
    }

    @Test
    fun `extractQuery ignores removed lines`() {
        // Удалённые строки (начинаются с -, но не ---) не должны давать токены.
        val diff = """
            +++ b/X.kt
            @@ -1 +1 @@
            -removedOldThing
            +addedNewThing
        """.trimIndent()
        val q = extractQueryFromDiff(diff)
        assertTrue(q.contains("addedNewThing"), "should include added identifier")
        assertFalse(q.contains("removedOldThing"), "should NOT include removed identifier")
    }

    @Test
    fun `extractQuery skips dev_null file`() {
        val diff = """
            +++ b/dev/null
            +++ b/Real.kt
        """.trimIndent()
        val q = extractQueryFromDiff(diff)
        assertTrue(q.contains("Real"))
        assertFalse(q.contains("null"), "'dev/null' should be skipped, got: '$q'")
    }

    @Test
    fun `extractQuery limits number of tokens`() {
        // Генерируем diff с 100 идентификаторами — должны получить ≤40 (MAX_QUERY_TOKENS).
        val idents = (1..100).joinToString("\n") { "+identifier$it = $it" }
        val diff = "+++ b/F.kt\n$idents"
        val q = extractQueryFromDiff(diff)
        val tokenCount = q.split("\\s+".toRegex()).filter { it.isNotBlank() }.size
        assertTrue(tokenCount <= 40, "should limit tokens to ≤40, got $tokenCount: '$q'")
    }

    // ── buildReviewPrompt ─────────────────────────────────────────────────────

    @Test
    fun `buildReviewPrompt returns system then user message`() {
        val msgs = buildReviewPrompt("+some diff", null)
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("user", msgs[1].role)
    }

    @Test
    fun `buildReviewPrompt includes diff in user message`() {
        val diff = "+val x = calculateSomething()"
        val msgs = buildReviewPrompt(diff, null)
        assertTrue(msgs[1].content.contains(diff), "user message should contain the diff")
        assertTrue(msgs[1].content.contains("```diff"))
    }

    @Test
    fun `buildReviewPrompt prepends codeReviewer system prompt`() {
        val msgs = buildReviewPrompt("+x", null)
        assertTrue(msgs[0].content.startsWith(SystemPrompts.codeReviewer.content.trim()))
    }

    @Test
    fun `buildReviewPrompt embeds RAG block when provided`() {
        val rag = "[Retrieved context]\n[1] (AGENTS.md › Stack)\nKotlin"
        val msgs = buildReviewPrompt("+x", rag)
        assertTrue(msgs[0].content.contains("[Retrieved context]"))
        assertTrue(msgs[0].content.contains("Kotlin"))
    }

    @Test
    fun `buildReviewPrompt omits RAG section when null`() {
        // Используем чистый system-prompt без упоминания «Retrieved context».
        val msgs = buildReviewPrompt("+x", null)
        // codeReviewer prompt упоминает [Retrieved context] в описании, но сам БЛОК не должен
        // добавляться — проверяем что RAG-контент не утёк.
        assertFalse(msgs[0].content.contains("[1] ("))
    }

    @Test
    fun `codeReviewer system prompt exists with required sections`() {
        // Sanity: промпт должен требовать структурированный ответ (баги/архитектура/рекомендации).
        assertNotNull(SystemPrompts.codeReviewer)
        val content = SystemPrompts.codeReviewer.content
        assertTrue(content.contains("баг", ignoreCase = true) || content.contains("bug", ignoreCase = true))
        assertTrue(content.contains("Архитектур", ignoreCase = true) || content.contains("rchitect", ignoreCase = true))
        assertTrue(content.contains("Рекомендаци", ignoreCase = true) || content.contains("ecommend", ignoreCase = true))
    }
}
