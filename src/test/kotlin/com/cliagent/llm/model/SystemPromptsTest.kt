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

    // ── День 31 (cleanup): localRagCompare — единый промпт для /rag compare-local ─

    @Test
    fun `localRagCompare prompt is non-blank`() {
        val content = SystemPrompts.localRagCompare.content
        assertTrue(content.isNotBlank(), "localRagCompare must be a non-empty prompt")
    }

    @Test
    fun `localRagCompare enforces answer only from context and source naming`() {
        // Объединённые требования из localRag: ONLY from context + source naming.
        val content = SystemPrompts.localRagCompare.content.lowercase()
        assertTrue(content.contains("retrieved context") || content.contains("context"),
            "localRagCompare must reference context")
        assertTrue(content.contains("only") || content.contains("do not invent"),
            "localRagCompare must enforce answering only from context")
        assertTrue(content.contains("source"), "localRagCompare must require naming source")
    }

    @Test
    fun `localRagCompare includes format markers Answer Sources Citations`() {
        // Из бывшего DEFAULT: формат ответа (Answer/Sources/Citations) — даёт CitationDetector шанс.
        val content = SystemPrompts.localRagCompare.content
        assertTrue(content.contains("Answer"), "localRagCompare must have Answer format marker")
        assertTrue(content.contains("Sources"), "localRagCompare must have Sources format marker")
        assertTrue(content.contains("Citations"), "localRagCompare must have Citations format marker")
    }

    @Test
    fun `localRagCompare includes не знаю fallback and concise constraint`() {
        // Из localRag: «не знаю» + concise (анти-галлюцинации + длина для 14B).
        val content = SystemPrompts.localRagCompare.content.lowercase()
        assertTrue(content.contains("не знаю") || content.contains("don't know"),
            "localRagCompare must include не знаю fallback")
        assertTrue(content.contains("concise") || content.contains("word"),
            "localRagCompare must limit response length")
    }

    // ── День 30: motivator — веб-агент «Мотиватор» (локальная qwen2.5:7b) ─

    @Test
    fun `motivator prompt is non-blank and in russian`() {
        val content = SystemPrompts.motivator.content
        assertTrue(content.isNotBlank(), "motivator must be a non-empty prompt")
        // Основной язык — русский: ключевые слова инструкции присутствуют.
        assertTrue(content.contains("Мотиватор") || content.contains("мотив"),
            "motivator must reference motivation in Russian")
    }

    @Test
    fun `motivator acknowledges partial progress`() {
        // Ключевая задача агента: признавать частично сделанное (прогресс важнее идеала).
        val content = SystemPrompts.motivator.content.lowercase()
        assertTrue(content.contains("част") || content.contains("прогресс"),
            "motivator must acknowledge partial progress")
    }

    @Test
    fun `motivator references user-specific facts not invented`() {
        // Анти-галлюцинация: модель должна опираться на конкретику сообщения, не выдумывать.
        val content = SystemPrompts.motivator.content.lowercase()
        assertTrue(content.contains("конкрет") || content.contains("сообщения пользователя"),
            "motivator must ground response in user's specifics")
        assertTrue(content.contains("не выдумывай") || content.contains("do not invent"),
            "motivator must prohibit inventing facts")
    }

    @Test
    fun `motivator limits response length for 7b model`() {
        // 7B многословна на CPU → явный лимит (2–5 предложений) помогает скорости и качеству.
        val content = SystemPrompts.motivator.content.lowercase()
        assertTrue(content.contains("2") && content.contains("5") && content.contains("предложени"),
            "motivator must limit response to 2-5 sentences for the 7B model")
    }

    @Test
    fun `motivator is concise for small context window`() {
        // Окно контекста = 5 сообщений (SlidingWindowStrategy), промпт должен быть лаконичным.
        val len = SystemPrompts.motivator.content.length
        assertTrue(len < 1200, "motivator should be concise (< 1200 chars) for sliding-window 5, got $len")
    }
}
