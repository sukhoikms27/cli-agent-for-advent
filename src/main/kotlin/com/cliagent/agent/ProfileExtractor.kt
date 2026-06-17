package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.memory.UserProfile

/**
 * LLM-извлечение [UserProfile] из диалога (день 12 — персонализация).
 * Переиспользует паттерн [com.cliagent.context.strategy.StickyFactsStrategy.updateFacts]:
 * промпт → [llmClient.chat] (temperature = 0.0) → парсинг строк.
 *
 * Используется:
 *  - on-demand через `/profile extract`;
 *  - автоматически каждые N ходов (opt-in `--auto-profile`, см. [com.cliagent.agent.ContextAwareAgent]).
 *
 * На ошибку LLM возвращает [current] без изменений (как StickyFacts «keep as-is»).
 */
class ProfileExtractor(
    private val llmClient: LlmClient,
    private val model: String
) {
    suspend fun extract(history: List<ChatMessage>, current: UserProfile?): UserProfile {
        val recent = history.takeLast(RECENT_TURNS)
        if (recent.isEmpty()) return current ?: UserProfile()

        val extractionPrompt = """
            Проанализируй диалог и выведи профиль пользователя.
            Определи по сообщениям: предпочитаемый стиль ответов, формат, контекст (кто пользователь, его цель) и ограничения (стек, запреты, правила).
            Выводи только то, что явно следует из диалога. Не выдумывай.

            Текущий профиль:
            ${current?.let { renderCurrent(it) } ?: "(пусто)"}

            Последние сообщения диалога:
            ${recent.joinToString("\n") { "[${it.role}]: ${it.content}" }}

            Выведи строго в формате (пустые строки оставляй пустыми):
            style: ...
            format: ...
            about: ...
            constraints:
            - ...
            - ...
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = extractionPrompt)),
            temperature = 0.0
        )

        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val inferred = parseProfile(result.data.choices.first().message.content)
                mergeProfile(current, inferred)
            }
            is LlmResult.Error -> current ?: UserProfile()  // keep as-is
        }
    }

    /**
     * Аккумулирует профиль: сохраняет существующие поля если inferred пуст,
     * объединяет constraints с дедупликацией.
     */
    fun mergeProfile(current: UserProfile?, inferred: UserProfile): UserProfile = UserProfile(
        style = inferred.style ?: current?.style,
        format = inferred.format ?: current?.format,
        about = inferred.about ?: current?.about,
        constraints = ((current?.constraints ?: emptyList()) + inferred.constraints)
            .filter { it.isNotBlank() }
            .distinct()
    )

    private fun parseProfile(text: String): UserProfile {
        var style: String? = null
        var format: String? = null
        var about: String? = null
        val constraints = mutableListOf<String>()
        var inConstraints = false

        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("style:", ignoreCase = true) -> {
                    inConstraints = false
                    style = trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("format:", ignoreCase = true) -> {
                    inConstraints = false
                    format = trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("about:", ignoreCase = true) -> {
                    inConstraints = false
                    about = trimmed.substringAfter(":").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("constraints:", ignoreCase = true) -> {
                    inConstraints = true
                }
                inConstraints && trimmed.startsWith("-") -> {
                    trimmed.removePrefix("-").trim().takeIf { it.isNotBlank() }
                        ?.let(constraints::add)
                }
            }
        }
        return UserProfile(style = style, format = format, about = about, constraints = constraints)
    }

    private fun renderCurrent(profile: UserProfile): String = buildString {
        profile.style?.let { appendLine("style: $it") }
        profile.format?.let { appendLine("format: $it") }
        profile.about?.let { appendLine("about: $it") }
        if (profile.constraints.isNotEmpty()) {
            appendLine("constraints:")
            profile.constraints.forEach { appendLine("- $it") }
        }
    }.trimEnd()

    companion object {
        private const val RECENT_TURNS = 10
    }
}
