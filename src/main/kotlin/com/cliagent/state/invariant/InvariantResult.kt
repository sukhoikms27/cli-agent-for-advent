package com.cliagent.state.invariant

/**
 * Результат проверки текста (запроса или ответа) на соответствие инвариантам (день 14).
 *
 * - [Valid] — нарушений нет, текст можно принять/выполнить.
 * - [Violated] — найдено нарушение; [Violated.explanation] — человекочитаемое пояснение (какое
 *   правило, почему нарушено) для показа пользователю и feedback-промпта при retry.
 *
 * Sealed-класс по конвенции CLAUDE.md (обработка ошибок через Result-паттерны, не исключения).
 * Зеркало `LlmResult<T>` / `AgentResult` — единый стиль Result'ов в проекте.
 */
sealed class InvariantResult {
    /** Нарушений нет. `object` — один экземпляр (нет данных для возврата). */
    object Valid : InvariantResult()

    /**
     * Найдено нарушение инварианта.
     *
     * @param ruleId      id нарушенного [Invariant] (для ссылок в `/invariants`)
     * @param rule        текст правила (для отображения в отказе)
     * @param explanation почему нарушено (от judge'а — для feedback-промпта и отказа пользователю)
     */
    data class Violated(
        val ruleId: String,
        val rule: String,
        val explanation: String
    ) : InvariantResult()
}
