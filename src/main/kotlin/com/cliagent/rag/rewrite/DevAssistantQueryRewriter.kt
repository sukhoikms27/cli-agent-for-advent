package com.cliagent.rag.rewrite

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest

/**
 * День 31 (fix multilingual RAG): специализированный query rewriter для dev-assistant'а.
 *
 * Отличается от [LlmQueryRewriter] недели 5 тем, что **переводит запрос на английский** перед
 * эмбеддингом. Это критично для embedding-модели `nomic-embed-text`, которая англоязычная:
 * семантический матч русского «технический стек» против английского `## Tech Stack | Kotlin |
 * Gradle` слабее, чем английский «tech stack frameworks» против той же таблицы.
 *
 * Промпт LlmQueryRewriter'а недели 5 говорит «сохраняй язык» — он НЕ переводит, поэтому
 * мультиязычные запросы к англоязычному корпусу матчатся плохо. Здесь — явный перевод +
 * добавление технических синонимов для code/документации.
 *
 * **Мягкая деградация** (как [LlmQueryRewriter]): `LlmResult.Error` → вернуть исходный `query`.
 * `CancellationException` пробрасывается (AGENTS.md).
 *
 * **Используется только в [com.cliagent.cli.AskCommand]** (dev-assistant). Основной REPL-чат
 * остаётся на [LlmQueryRewriter] (конфиг config.rag.queryRewriter) — backward-compat.
 *
 * @param client LLM-клиент (z.ai/ollama) — НЕ эмбеддер
 * @param model  имя модели (напр. "glm-5.1")
 */
class DevAssistantQueryRewriter(
    private val client: LlmClient,
    private val model: String,
) : QueryRewriter {
    override val name: String = "dev-assistant-llm"

    override suspend fun rewrite(query: String): String {
        if (query.isBlank()) return query
        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SYSTEM_PROMPT),
                ChatMessage(role = "user", content = query),
            ),
            // Низкая температура → устойчивый перевод без галлюцинаций.
            temperature = 0.2,
            // Бюджет: thinking-модели (GLM-5.1, qwen3) тратят много токенов на внутренний reasoning ДО
            // content. На русских промптах GLM-5.1 при maxTokens=256 отдавала completionTokens=256 с
            // ПУСТЫМ content (вся квота — reasoning). 1024 даёт reasoning'у закончиться и выдать ответ.
            maxTokens = 1024,
        )
        return when (val result = client.chat(request)) {
            is LlmResult.Error -> query // мягкая деградация: исходный запрос (как LlmQueryRewriter)
            is LlmResult.Success -> {
                val content = result.data.choices.firstOrNull()?.message?.content
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                content ?: query // пустой ответ → исходный запрос
            }
        }
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are a query translator for an English-language code/documentation search index. " +
                "Translate the user's question into English (if it's not already English), then expand " +
                "with technical synonyms and key terms relevant to software projects " +
                "(frameworks, languages, build tools, dependencies, architecture, APIs). " +
                "Remove conversational filler and stop words. Keep the original meaning. " +
                "Return ONLY the reformulated English search query on a single line, no quotes, no explanation."
    }
}
