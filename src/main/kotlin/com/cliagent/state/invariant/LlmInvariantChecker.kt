package com.cliagent.state.invariant

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Семантическая проверка инвариантов через LLM-as-judge (день 14).
 *
 * Stateless-утилитарный класс по образцу [com.cliagent.agent.ProfileExtractor] (день 12):
 * один короткий LLM-вызов → парсинг JSON. **Не агент** — нет истории/контекста/стейта, каждый
 * вызов свежий (для проверки «нарушает ли текст правило» контекст прошлых ходов — шум).
 *
 * Промпт обязывает модель ответить строго JSON `{"violated":bool,"ruleId":"...","explanation":"..."}`.
 * `temperature = 0.0` для детерминированности.
 *
 * Fallback-safe: на ошибку LLM, неразборный JSON или пустой список инвариантов возвращает
 * [InvariantResult.Valid] — судья не должен блокировать пользователя при сбое (лучше пропустить
 * нарушение, чем зависнуть). См. риск #2 в `plan/finisheddays/day-14/README.md`.
 */
class LlmInvariantChecker(
    private val llmClient: LlmClient,
    private val model: String
) : InvariantChecker {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkRequest(text: String, invariants: List<Invariant>): InvariantResult =
        judge("запрос пользователя", "запрашивает ли пользователь решение, нарушающее правило", text, invariants)

    override suspend fun checkResponse(text: String, invariants: List<Invariant>): InvariantResult =
        judge("ответ ассистента", "содержит ли предложенное решение нарушение правила", text, invariants)

    /**
     * Общий judge-вызов. [what] — что проверяем (запрос/ответ), [question] — формулировка вопроса.
     * Разные формулировки дают разный акцент судьи (запрос — «хочет ли...», ответ — «содержит ли...»).
     */
    private suspend fun judge(
        what: String,
        question: String,
        text: String,
        invariants: List<Invariant>
    ): InvariantResult {
        if (invariants.isEmpty()) return InvariantResult.Valid   // fast-path: нечего проверять

        val prompt = buildJudgePrompt(what, question, text, invariants)
        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = prompt)),
            temperature = 0.0
        )
        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val content = result.data.choices.firstOrNull()?.message?.content.orEmpty()
                parseVerdict(content, invariants)
            }
            is LlmResult.Error -> InvariantResult.Valid   // fallback-safe: не блокируем
        }
    }

    private fun buildJudgePrompt(
        what: String,
        question: String,
        text: String,
        invariants: List<Invariant>
    ): String = buildString {
        appendLine("Ты — строгий ревьюер инвариантов проекта. Проверь $what на соответствие правилам.")
        appendLine("Правила проекта (id → правило):")
        invariants.forEach { appendLine("  [${it.id}] ${it.rule}") }
        appendLine()
        appendLine("Вопрос: $question?")
        appendLine("$what для проверки:")
        appendLine(text)
        appendLine()
        appendLine("Ответь СТРОГО JSON без markdown-обёртки:")
        appendLine("""{"violated": true|false, "ruleId": "id-нарушенного-правила", "explanation": "почему нарушено"}""")
        appendLine("Если violated=false — ruleId и explanation пустые строки.")
    }

    /**
     * Парсинг вердикта. Толерантный: снимает markdown-обёртку ```json ... ```.
     * Неразборный/musora JSON → [InvariantResult.Valid] (fallback-safe).
     */
    private fun parseVerdict(content: String, invariants: List<Invariant>): InvariantResult {
        val cleaned = content.trim().removeMarkdownFence()
        val parsed = try {
            json.parseToJsonElement(cleaned).jsonObject
        } catch (e: Exception) {
            return InvariantResult.Valid   // неразборный JSON → не блокируем
        }
        val violated = parsed["violated"]?.jsonPrimitive?.booleanOrNull() ?: false
        if (!violated) return InvariantResult.Valid

        val ruleId = parsed["ruleId"]?.jsonPrimitive?.contentOrNull().orEmpty()
        val explanation = parsed["explanation"]?.jsonPrimitive?.contentOrNull().orEmpty()
        // rule достаём из списка по id; если id не найден — берём первое правило (best-effort)
        val rule = invariants.firstOrNull { it.id == ruleId } ?: invariants.first()
        return InvariantResult.Violated(
            ruleId = if (ruleId.isNotBlank()) ruleId else rule.id,
            rule = rule.rule,
            explanation = explanation.ifBlank { "нарушено правило [${rule.id}]: ${rule.rule}" }
        )
    }

    /** Снимает ```json ... ``` / ``` ... ``` обёртку. */
    private fun String.removeMarkdownFence(): String {
        val s = this.trim()
        if (!s.startsWith("```")) return s
        val withoutLang = s.removePrefix("```json").removePrefix("```")
        return withoutLang.removeSuffix("```").trim()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.booleanOrNull(): Boolean? =
        try { boolean } catch (e: Exception) { null }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (this.isString) content else null

    @Suppress("unused")
    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
}
