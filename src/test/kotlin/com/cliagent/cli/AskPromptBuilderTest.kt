package com.cliagent.cli

import com.cliagent.llm.model.SystemPrompts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 31 — unit-тесты чистых функций сборки промпта dev-assistant'а ([buildAskPrompt],
 * [formatRagBlock]). Без IO, без LLM — детерминированные функции (как ReportFormatTest в mcp-server).
 */
class AskPromptBuilderTest {

    private val systemPrompt = SystemPrompts.devAssistant.content

    @Test
    fun `buildAskPrompt returns system message first, user message second`() {
        val msgs = buildAskPrompt("какой стек?", "branch: main", null, systemPrompt)
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("user", msgs[1].role)
        assertEquals("какой стек?", msgs[1].content)
    }

    @Test
    fun `buildAskPrompt embeds git context into system message`() {
        val msgs = buildAskPrompt("q", "branch: feature/x | 2 changed: A.kt, B.kt", null, systemPrompt)
        assertTrue(msgs[0].content.contains("[Project git context]"))
        assertTrue(msgs[0].content.contains("branch: feature/x"))
        assertTrue(msgs[0].content.contains("2 changed"))
    }

    @Test
    fun `buildAskPrompt embeds RAG block when provided`() {
        val rag = "[Retrieved context]\n[1] (README.md › Stack)\nKotlin + Spring"
        val msgs = buildAskPrompt("q", "branch: main", rag, systemPrompt)
        assertTrue(msgs[0].content.contains("[Retrieved context]"))
        assertTrue(msgs[0].content.contains("Kotlin + Spring"))
    }

    @Test
    fun `buildAskPrompt omits RAG section when null`() {
        // Используем кастомный system-prompt без упоминания «Retrieved context», чтобы проверка
        // была изолированной (SystemPrompts.devAssistant упоминает его в описании tools).
        val cleanPrompt = "You are a dev assistant."
        val msgs = buildAskPrompt("q", "branch: main", null, cleanPrompt)
        assertFalse(msgs[0].content.contains("[Retrieved context]"))
    }

    @Test
    fun `buildAskPrompt prepends devAssistant system prompt content`() {
        val msgs = buildAskPrompt("q", "branch: main", null, systemPrompt)
        // Содержимое devAssistant-промпта должно быть в начале system-сообщения.
        assertTrue(msgs[0].content.startsWith(systemPrompt.trim()))
    }

    // ── formatRagBlock ────────────────────────────────────────────────────────

    @Test
    fun `formatRagBlock empty list returns empty string`() {
        assertEquals("", formatRagBlock(emptyList()))
    }

    @Test
    fun `formatRagBlock wraps content in Retrieved context header`() {
        val hits = listOf(testChunk("README.md", "Stack", "Kotlin and Spring Boot"))
        val block = formatRagBlock(hits)
        assertTrue(block.startsWith("[Retrieved context]"))
        assertTrue(block.contains("Kotlin and Spring Boot"))
    }

    @Test
    fun `formatRagBlock numbers chunks sequentially`() {
        val hits = listOf(
            testChunk("A.md", "s1", "content A"),
            testChunk("B.md", "s2", "content B"),
        )
        val block = formatRagBlock(hits)
        assertTrue(block.contains("[1]"))
        assertTrue(block.contains("[2]"))
        assertTrue(block.contains("content A"))
        assertTrue(block.contains("content B"))
    }

    @Test
    fun `formatRagBlock shows source and section`() {
        val hits = listOf(testChunk("AGENTS.md", "Tech Stack", "Kotlin JVM 21"))
        val block = formatRagBlock(hits)
        assertTrue(block.contains("(AGENTS.md › Tech Stack)"))
    }

    @Test
    fun `formatRagBlock shows source only when section blank`() {
        val hits = listOf(testChunk("README.md", "", "just text"))
        val block = formatRagBlock(hits)
        assertTrue(block.contains("(README.md)"))
        assertFalse(block.contains("README.md ›"))
    }

    @Test
    fun `formatRagBlock trims chunk text`() {
        val hits = listOf(testChunk("X.md", "s", "   spaced content   "))
        val block = formatRagBlock(hits)
        assertTrue(block.contains("spaced content"))
        assertFalse(block.contains("spaced content   "))
    }

    @Test
    fun `devAssistant system prompt exists and is non-empty`() {
        // Sanity-check: промпт дев-ассистента должен быть определён (не пустой, содержит ключевые
        // правила — отвечать из контекста, называть источник, «не знаю»).
        assertNotNull(SystemPrompts.devAssistant)
        assertTrue(SystemPrompts.devAssistant.content.isNotBlank())
        assertTrue(SystemPrompts.devAssistant.content.contains("Retrieved context"))
        assertTrue(SystemPrompts.devAssistant.content.contains("не знаю"))
    }
}
