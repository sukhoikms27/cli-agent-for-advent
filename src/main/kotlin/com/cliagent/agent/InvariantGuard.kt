package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.state.invariant.Invariant
import com.cliagent.state.invariant.InvariantChecker
import com.cliagent.state.invariant.InvariantResult

/**
 * Decorator поверх [Agent], обеспечивающий соблюдение инвариантов (день 14, третий столп недели 3).
 *
 * Программная проверка (defense-in-depth слой 2 — в коде; слой 1 — в промпте через
 * [PromptBuilder.renderInvariantsBlock]):
 *  - **Запрос** ([InvariantChecker.checkRequest]): Violated → отказ **без** основного LLM-вызова
 *    (экономия токенов + жёсткое соблюдение; соответствует требованию day14 «отказывается
 *    предлагать решения, которые их нарушают»). Отказ содержит rule и explanation.
 *  - **Ответ** ([InvariantChecker.checkResponse]): Violated → retry-loop с feedback-промптом
 *    (модель переделывает, max [MAX_RETRIES] попыток); при исчерпании — fallback с `⚠️`.
 *
 * Конвенция CLAUDE.md: decorator не меняет проверенный [ContextAwareAgent] (его тесты не
 * трогаются); checker через абстракцию; suspend для IO; `CancellationException` не глотается.
 *
 * @param delegated           оборачиваемый агент (обычно [ContextAwareAgent]).
 * @param checker             стратегия проверки (обычно [com.cliagent.state.invariant.LlmInvariantChecker]).
 * @param invariantsProvider  колбэк, достающий текущие инварианты (развязка от конкретного класса
 *                            агента: guard знает только интерфейс [Agent]); в `ChatCommand`:
 *                            `InvariantGuard(base, checker) { base.getInvariants() }`.
 */
class InvariantGuard(
    private val delegated: Agent,
    private val checker: InvariantChecker,
    private val invariantsProvider: suspend () -> List<Invariant>
) : Agent {

    override suspend fun chat(userMessage: String): String {
        val invariants = invariantsProvider()
        // fast-path: нечего проверять — ноль накладных (поведение = оборачиваемый агент)
        if (invariants.isEmpty()) return delegated.chat(userMessage)

        // 1. Проверка ЗАПРОСА — отказ без LLM
        val reqResult = checker.checkRequest(userMessage, invariants)
        if (reqResult is InvariantResult.Violated) {
            return refuseMessage(reqResult)
        }

        // 2. Генерация + проверка ОТВЕТА — retry-loop
        var response = delegated.chat(userMessage)
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            val respResult = checker.checkResponse(response, invariants)
            if (respResult is InvariantResult.Valid) return response
            // Violated — просим модель переделать
            attempt++
            response = delegated.chat(feedbackMessage(respResult as InvariantResult.Violated, userMessage))
        }
        // 3. Fallback — не удалось соблюсти за MAX_RETRIES
        return fallbackMessage(response)
    }

    override suspend fun getHistory(): List<ChatMessage> = delegated.getHistory()

    override suspend fun reset() = delegated.reset()

    private fun refuseMessage(v: InvariantResult.Violated): String =
        "⛔ Не могу выполнить этот запрос: он нарушает инвариант проекта.\n" +
            "   Правило: ${v.rule}\n" +
            "   Причина: ${v.explanation}\n" +
            "   Переформулируйте запрос в рамках заданных ограничений."

    private fun feedbackMessage(v: InvariantResult.Violated, originalRequest: String): String =
        "Твой предыдущий ответ нарушает инвариант проекта: «${v.rule}» (${v.explanation}). " +
            "Переделай ответ, строго соблюдая это правило. Исходный запрос пользователя: $originalRequest"

    private fun fallbackMessage(lastResponse: String): String =
        "⚠️ Внимание: не удалось полностью соблюсти инварианты проекта за $MAX_RETRIES попыток.\n" +
            "Последний ответ (требует ручной проверки):\n\n$lastResponse"

    private companion object {
        const val MAX_RETRIES = 3
    }
}
