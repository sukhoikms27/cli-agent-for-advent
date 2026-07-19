package com.cliagent.support

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * День 33 — LLM-extractor: извлекает из свободного текста пользователя номера тикетов и email'ы.
 *
 * **Мотивация.** Пользователь пишет «что с моим тикетом 5?» или «почта alice@example.com, когда
 * решат?» — нельзя требовать от него заполнять формальные поля Ticket ID/Email в UI. Этот
 * extractor вызывается ДО основного запроса агента, чтобы достать структурированные поля из
 * текста и подставить в [SupportAgentFactory.createFor] для построения `[Ticket context]` блока.
 *
 * **Референс.** Скелет по образцу [com.cliagent.state.invariant.LlmInvariantChecker]
 * (JSON-output + soft-degradation) + [com.cliagent.rag.rewrite.DevAssistantQueryRewriter]
 * (`maxTokens=1024` — фикс thinking-моделей GLM-5.1/qwen3:14b, которые иначе тратят весь бюджет
 * на reasoning и возвращают пустой content).
 *
 * **Stability contract:**
 * - Любая ошибка (LLM недоступна, невалидный JSON, garbage output) → [ExtractedFields.empty],
 *   основной запрос продолжается без извлечённых полей. Никаких exception наружу.
 * - `CancellationException` пробрасывается (конвенция AGENTS.md: never swallow).
 * - Blank input → fast-path без LLM-вызова.
 *
 * @param client LLM-клиент (тот же, что у агента — shared connection).
 * @param model имя модели для ChatRequest (zai: glm-5.1, ollama: qwen3:14b).
 */
class TicketFieldExtractor(
    private val client: LlmClient,
    private val model: String,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Извлечь ticketId и customerEmail из [userText].
     *
     * @return [ExtractedFields.empty] если: текст пустой / LLM-ошибка / невалидный JSON /
     *   модель не нашла полей. Иначе — [ExtractedFields] с заполненными (возможно одним) полями.
     */
    suspend fun extract(userText: String): ExtractedFields {
        if (userText.isBlank()) return ExtractedFields.empty

        val prompt = buildPrompt(userText)
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.0,
            // maxTokens=1024 — фикс thinking-моделей: без явного потолка qwen3:14b/GLM-5.1
            // тратят токены на <think> до content и возвращают пустую строку.
            // См. DevAssistantQueryRewriter.kt:43-48 для деталей.
            maxTokens = 1024,
        )
        return when (val result = client.chat(request)) {
            is LlmResult.Success -> {
                val content = result.data.choices.firstOrNull()?.message?.content.orEmpty()
                parseFields(content)
            }
            is LlmResult.Error -> ExtractedFields.empty
        }
    }

    /** Сборка промпта с few-shot примерами и JSON-шаблоном ответа. */
    private fun buildPrompt(userText: String): String = buildString {
        appendLine("Извлеки из сообщения пользователя номер тикета (ticketId) и email (customerEmail),")
        appendLine("если они упомянуты в свободной форме. Если поля не упомянуты — верни null.")
        appendLine()
        appendLine("Примеры:")
        appendLine("- «что с моим тикетом 5?» → {\"ticketId\": 5, \"customerEmail\": null}")
        appendLine("- «заявка #42 по моей проблеме» → {\"ticketId\": 42, \"customerEmail\": null}")
        appendLine("- «почта alice@example.com, когда решат?» → {\"ticketId\": null, \"customerEmail\": \"alice@example.com\"}")
        appendLine("- «обращался по тикету 17, моя почта bob@mail.ru» → {\"ticketId\": 17, \"customerEmail\": \"bob@mail.ru\"}")
        appendLine("- «просто вопрос о продукте» → {\"ticketId\": null, \"customerEmail\": null}")
        appendLine()
        appendLine("Ответь СТРОГО JSON без markdown-обёртки и без пояснений:")
        appendLine("""{"ticketId": <int|null>, "customerEmail": <string|null>}""")
        appendLine()
        appendLine("Сообщение пользователя: \"$userText\"")
    }

    /**
     * Толерантный парсинг JSON-ответа LLM. Любая ошибка → [ExtractedFields.empty].
     *
     * Снимает markdown-обёртку ` ```json ... ``` `, достаёт JSON-объект через regex-fallback
     * (если LLM добавила преамбулу/пояснение), валидирует типы полей.
     */
    private fun parseFields(content: String): ExtractedFields {
        val cleaned = content.trim().removeMarkdownFence()
        val jsonObj: JsonObject = try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (e: Exception) {
            // Regex-fallback: достаём первый {...}-блок из любого окружения.
            val match = Regex("""\{[^{}]*}""", RegexOption.DOT_MATCHES_ALL).find(cleaned)
            if (match != null) {
                try {
                    json.parseToJsonElement(match.value).jsonObject
                } catch (e2: Exception) {
                    return ExtractedFields.empty
                }
            } else {
                return ExtractedFields.empty
            }
        }

        val ticketId = jsonObj["ticketId"]?.jsonPrimitive?.intOrNullSafe()
        val customerEmail = jsonObj["customerEmail"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "null" }
        return ExtractedFields(ticketId, customerEmail)
    }

    /**
     * Безопасное чтение Int: возвращает null если поле null/не число/строка "null".
     * LLM может вернуть `"ticketId": null` или `"ticketId": "null"` — оба случая → null.
     */
    private fun kotlinx.serialization.json.JsonPrimitive.intOrNullSafe(): Int? {
        if (this.toString() == "null") return null
        return intOrNull
    }

    /** Снимает ```json ... ``` / ``` ... ``` обёртку (копия из LlmInvariantChecker). */
    private fun String.removeMarkdownFence(): String {
        val s = this.trim()
        if (!s.startsWith("```")) return s
        val withoutLang = s.removePrefix("```json").removePrefix("```")
        return withoutLang.removeSuffix("```").trim()
    }
}

/**
 * Извлечённые из текста поля. [empty] — canonical "ничего не извлечено".
 *
 * @param ticketId номер тикета (если упомянут), null иначе.
 * @param customerEmail email пользователя (если упомянут), null иначе.
 */
data class ExtractedFields(
    val ticketId: Int?,
    val customerEmail: String?,
) {
    val isEmpty: Boolean get() = ticketId == null && customerEmail == null

    companion object {
        val empty = ExtractedFields(ticketId = null, customerEmail = null)
    }
}
