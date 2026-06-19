package com.cliagent.state.invariant

/**
 * Программная проверка инвариантов (день 14, третий столп недели 3).
 *
 * Точка абстракции курса: «интерфейс/абстрактный класс с функцией `check`»
 * (`plan/videossummary/03-memory-state-notes.md:373`). Позволяет менять стратегию проверки
 * (LLM-judge ↔ keyword ↔ композиция) без правок потребителей.
 *
 * Defense-in-depth вместе с [com.cliagent.agent.PromptBuilder] (инварианты в промпте):
 * текстовые правила могут потеряться после сжатия истории → нужна кодовая проверка.
 *
 * Два направления проверки (см. `03-memory-state-notes.md:375-381` — «response фильтруется по
 * инвариантам»; расширено запросом по требованию day14 «отказ при конфликте запрос↔инвариант»):
 *  - [checkRequest]  — проверка запроса пользователя ДО основного LLM-вызова.
 *                      Violated → агент отказывает без генерации ([com.cliagent.agent.InvariantGuard]).
 *  - [checkResponse] — проверка ответа модели ПОСЛЕ LLM-вызова.
 *                      Violated → retry-loop с feedback.
 *
 * Разделение методов даёт разный UX (отказ vs. переделка) и разные judge-формулировки.
 */
interface InvariantChecker {
    suspend fun checkRequest(text: String, invariants: List<Invariant>): InvariantResult
    suspend fun checkResponse(text: String, invariants: List<Invariant>): InvariantResult
}
