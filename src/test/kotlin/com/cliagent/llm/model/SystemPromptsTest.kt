package com.cliagent.llm.model

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 29: тесты task-specific system prompts для локальных RAG-моделей.
 *
 * `localRag` — адаптированный промпт для 14B-моделей (qwen3): явный, короткий, с жёстким
 * требованием источника и признания незнания. Проверяем ключевые инструкции присутствуют.
 */
class SystemPromptsTest {

    @Test
    fun `localRag prompt enforces answer only from context`() {
        val content = SystemPrompts.localRag.content.lowercase()
        assertTrue(content.contains("retrieved context") || content.contains("context"),
            "localRag must reference retrieved context")
        assertTrue(content.contains("only") || content.contains("не invent"),
            "localRag must enforce answering only from context")
    }

    @Test
    fun `localRag prompt requires naming source`() {
        val content = SystemPrompts.localRag.content.lowercase()
        assertTrue(content.contains("source"), "localRag must require naming source")
    }

    @Test
    fun `localRag prompt includes do-not-know fallback`() {
        // Анти-галлюцинация: модель должна признавать незнание (день 24 dontKnowThreshold).
        val content = SystemPrompts.localRag.content.lowercase()
        assertTrue(content.contains("не знаю") || content.contains("don't know") || content.contains("do not know"),
            "localRag must include не знаю / don't know fallback")
    }

    @Test
    fun `localRag prompt limits length for local models`() {
        // Локальные 14B модели многословны → явный лимит слов помогает скорости/качеству.
        val content = SystemPrompts.localRag.content.lowercase()
        assertTrue(content.contains("word") || content.contains("concise") || content.contains("150"),
            "localRag should limit response length for local models")
    }

    @Test
    fun `localRag is shorter than complex multi-section cloud prompts`() {
        // Локальные модели хуже следуют сложным инструкциям → localRag должен быть лаконичнее.
        // EXPERT_GROUP (PromptTemplates) — пример сложного cloud-промпта.
        val localLen = SystemPrompts.localRag.content.length
        // localRag не должен быть чрезмерно длинным (< 1000 символов — короткая явная инструкция).
        assertTrue(localLen < 1000, "localRag should be concise (< 1000 chars), got $localLen")
    }

    @Test
    fun `default prompt remains simple for backward compat`() {
        // default не должен меняться — backward-compat с днями 1-28 для cloud-сессий.
        val content = SystemPrompts.default.content
        assertTrue(content.length < 100, "default system prompt must stay short")
    }
}
