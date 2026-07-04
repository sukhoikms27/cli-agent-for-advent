package com.cliagent.rag

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 24: тесты [CitationDetector] — pure function, без моков. Покрывает детекцию источников
 * (basename из hits и expectedSources) и цитат (кавычки ≥15 символов, substring-overlap ≥40).
 *
 * Детектор используется в пост-чеке [com.cliagent.agent.ContextAwareAgent.finalizeAssistant]
 * (warning через logger) и в `/rag eval` (метрика % ответов с источниками/цитатами).
 */
class CitationDetectorTest {

    private fun chunk(id: String, source: String, text: String): ScoredChunk = ScoredChunk(
        RagChunk(chunkId = id, documentId = "d", source = source, title = "T", section = "S", text = text, index = 0),
        0.9f,
    )

    // ── источники (sourcesPresent) ──────────────────────────────────────────────────

    @Test
    fun `sources present when answer mentions basename from hits`() {
        val hits = listOf(chunk("c1", "context/SlidingWindow.kt", "keeps last N"))
        val r = CitationDetector.detect("Согласно SlidingWindow.kt, окно равно 10.", hits)
        assertTrue(r.sourcesPresent)
    }

    @Test
    fun `sources present via expectedSources even without hits`() {
        // /rag eval не передаёт hits (они внутри агента) — детектируем по expectedSources.
        val r = CitationDetector.detect(
            "Как описано в AGENTS.md, стек — Kotlin.",
            hits = emptyList(),
            expectedSources = listOf("AGENTS.md"),
        )
        assertTrue(r.sourcesPresent)
    }

    @Test
    fun `sources present case-insensitive`() {
        val hits = listOf(chunk("c1", "a/VectorMath.kt", "cosine formula"))
        val r = CitationDetector.detect("См. vectormath.kt для деталей.", hits)
        assertTrue(r.sourcesPresent)
    }

    @Test
    fun `sources absent when no source mentioned`() {
        val hits = listOf(chunk("c1", "a/VectorMath.kt", "cosine formula"))
        val r = CitationDetector.detect("Косинусное сходство считает похожесть векторов.", hits)
        assertFalse(r.sourcesPresent)
    }

    // ── цитаты (citationsPresent) ───────────────────────────────────────────────────

    @Test
    fun `citations present via guillemets with long content`() {
        val r = CitationDetector.detect("Вот дословная цитата: «косинусное сходство равно единице при идентичности».", emptyList())
        assertTrue(r.citationsPresent)
    }

    @Test
    fun `citations present via straight double quotes`() {
        val r = CitationDetector.detect("В документе сказано: \"chunking strategy splits documents into pieces\".", emptyList())
        assertTrue(r.citationsPresent)
    }

    @Test
    fun `citations present via substring overlap with chunk text`() {
        val longText = "The fixed chunking strategy splits documents into fixed-size windows with overlap."
        val hits = listOf(chunk("c1", "a.md", longText))
        // Ответ содержит дословный фрагмент чанка длиной ≥40 символов.
        val answer = "Стратегия fixed chunking: \"splits documents into fixed-size windows with\" по чанкам."
        val r = CitationDetector.detect(answer, hits)
        assertTrue(r.citationsPresent)
    }

    @Test
    fun `citations absent when no quotes and no overlap`() {
        val hits = listOf(chunk("c1", "a.md", "очень длинный текст чанка для проверки overlap"))
        val r = CitationDetector.detect("Короткий ответ без кавычек.", hits)
        assertFalse(r.citationsPresent)
    }

    @Test
    fun `citations absent for short quoted snippet below threshold`() {
        // Кавычки есть, но контент < 15 символов → не считается цитатой.
        val r = CitationDetector.detect("Параметр «alpha» равен 0.5.", emptyList())
        assertFalse(r.citationsPresent)
    }

    // ── edge cases ───────────────────────────────────────────────────────────────────

    @Test
    fun `empty answer returns both false`() {
        val hits = listOf(chunk("c1", "a.md", "some text"))
        val r = CitationDetector.detect("", hits)
        assertFalse(r.sourcesPresent)
        assertFalse(r.citationsPresent)
    }

    @Test
    fun `blank answer returns both false`() {
        val r = CitationDetector.detect("   ", emptyList())
        assertFalse(r.sourcesPresent)
        assertFalse(r.citationsPresent)
    }

    @Test
    fun `basename extracts filename from path`() {
        assertTrue(CitationDetector.basename("a/b/c.md") == "c.md")
        assertTrue(CitationDetector.basename("VectorMath.kt") == "VectorMath.kt")
        assertTrue(CitationDetector.basename("") == "")
        assertTrue(CitationDetector.basename("a\\b\\c.kt") == "c.kt")   // Windows-путь
    }
}
