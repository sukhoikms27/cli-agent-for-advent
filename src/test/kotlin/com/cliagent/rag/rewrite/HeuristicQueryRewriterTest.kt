package com.cliagent.rag.rewrite

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 23: тесты [HeuristicQueryRewriter]. Чистая функция — детерминированные проверки без моков.
 * Покрывает: удаление RU/EN стоп-слов, сохранение ключевых терминов, пунктуацию, edge-кейсы.
 */
class HeuristicQueryRewriterTest {

    private val rewriter = HeuristicQueryRewriter()

    @Test
    fun `identity rewriter returns query unchanged`() = runTest {
        assertEquals("strategy sliding", IdentityQueryRewriter.rewrite("strategy sliding"))
    }

    @Test
    fun `removes RU stop-words and preserves key terms`() = runTest {
        // «Какие стратегии есть в проекте?» — стоп: какие/есть/в/проекте; терм: стратегии
        val out = rewriter.rewrite("Какие стратегии есть в проекте?")
        assertEquals("стратегии", out)
    }

    @Test
    fun `removes EN stop-words and preserves key terms`() = runTest {
        val out = rewriter.rewrite("What is the sliding window strategy?")
        // what/is/the → стоп; sliding/window/strategy — термины
        assertEquals("sliding window strategy", out)
    }

    @Test
    fun `normalizes whitespace and punctuation`() = runTest {
        val out = rewriter.rewrite("  RAG,   retrieval!  augmented??  ")
        assertEquals("rag retrieval augmented", out)
    }

    @Test
    fun `returns empty for query of only stop-words`() = runTest {
        val out = rewriter.rewrite("что и в на это")
        assertEquals("", out)
    }

    @Test
    fun `returns empty for blank input`() = runTest {
        assertEquals("", rewriter.rewrite(""))
        assertEquals("", rewriter.rewrite("   "))
    }

    @Test
    fun `preserves digits and technical tokens`() = runTest {
        val out = rewriter.rewrite("topK is 5 by default")
        // is/by → стоп; topK/5/default — термины
        assertEquals("topk 5 default", out)
        assertTrue(out.contains("5"), "digit token preserved")
    }

    @Test
    fun `name property is heuristic`() {
        assertEquals("heuristic", HeuristicQueryRewriter().name)
    }

    @Test
    fun `enum fromString resolves aliases and defaults to IDENTITY`() {
        assertEquals(QueryRewriterType.IDENTITY, QueryRewriterType.fromString(null))
        assertEquals(QueryRewriterType.IDENTITY, QueryRewriterType.fromString(""))
        assertEquals(QueryRewriterType.IDENTITY, QueryRewriterType.fromString("identity"))
        assertEquals(QueryRewriterType.IDENTITY, QueryRewriterType.fromString("IDENTITY"))
        assertEquals(QueryRewriterType.IDENTITY, QueryRewriterType.fromString("none"))
        assertEquals(QueryRewriterType.HEURISTIC, QueryRewriterType.fromString("heuristic"))
        assertEquals(QueryRewriterType.HEURISTIC, QueryRewriterType.fromString("stopwords"))
        assertEquals(QueryRewriterType.LLM, QueryRewriterType.fromString("llm"))
        assertEquals(QueryRewriterType.LLM, QueryRewriterType.fromString("glm"))
        assertEquals(QueryRewriterType.IDENTITY, QueryRewriterType.fromString("unknown-value"))
    }
}
