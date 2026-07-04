package com.cliagent.rag.rewrite

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest

/**
 * День 23: LLM-based query rewrite. Один chat-запрос к LLM (z.ai GLM) — переформулирует запрос
 * пользователя в поисковый: синонимы, расширение аббревиатур (RAG→retrieval augmented generation),
 * удаление разговорной избыточности. Идея: улучшить полноту векторного поиска (recall), расширив
 * семантическое покрытие запроса.
 *
 * **Мягкая деградация (как день 22):** `LlmResult.Error` → вернуть исходный `query`. Ошибка LLM
 * не должна валить retrieval — агент ответит с запросом как есть (поведение дня 22).
 * `CancellationException` пробрасывается (корутины, AGENTS.md — никогда не глотать).
 *
 * @param client LLM-клиент (тот же z.ai, что и основная генерация) — НЕ эмбеддер. Lifecycle
 *               управляется владельцем (ChatCommand), как и общим embedder'ом.
 * @param model  имя модели (напр. "glm-5.1")
 */
class LlmQueryRewriter(
    private val client: LlmClient,
    private val model: String,
) : QueryRewriter {
    override val name: String = "llm"

    override suspend fun rewrite(query: String): String {
        if (query.isBlank()) return query
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = query),
            ),
            // Низкая температура → устойчивая, сфокусированная переформулировка (не креатив).
            temperature = 0.2,
            // Переформулировка короткая — бюджет ограничиваем, чтобы не получить эссе.
            maxTokens = 96,
        )
        return when (val result = client.chat(request)) {
            is LlmResult.Error -> query // мягкая деградация: исходный запрос
            is LlmResult.Success -> {
                val content = result.data.choices.firstOrNull()?.message?.content
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                content ?: query // пустой/бессодержательный ответ → исходный запрос
            }
        }
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "Ты — помощник, который переформулирует поисковые запросы для векторного семантического " +
                "поиска по документации. Расширяй аббревиатуры, добавляй синонимы ключевых терминов, " +
                "убирай разговорную избыточность и стоп-слова. Сохраняй исходный смысл и язык запроса. " +
                "Верни ТОЛЬКО переформулированный запрос одной строкой, без пояснений и кавычек."
    }
}
