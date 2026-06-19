# Задача 02. Результат проверки (`InvariantResult`)

## Что
Sealed-результат проверки инвариантов — то, что возвращает `InvariantChecker.check*()`. По
конвенции CLAUDE.md (sealed Result-паттерн, без исключений для flow control).

## Зависимости
01 (`Invariant.id` используется в `Violated.ruleId`).

## Реализация
Новый файл `src/main/kotlin/com/cliagent/state/invariant/InvariantResult.kt`:

```kotlin
package com.cliagent.state.invariant

/**
 * Результат проверки текста (запроса или ответа) на соответствие инвариантам (день 14).
 *
 * - [Valid] — нарушений нет, текст можно принять/выполнить.
 * - [Violated] — найдено нарушение; explanation — человекочитаемое пояснение (какое правило,
 *   почему нарушено) для показа пользователю и feedback-промпта при retry.
 *
 * Sealed-класс по конвенции CLAUDE.md (обработка ошибок через Result-паттерны, не исключения).
 */
sealed class InvariantResult {
    object Valid : InvariantResult()

    data class Violated(
        val ruleId: String,       // id нарушенного [Invariant] (для /invariants ссылок)
        val rule: String,         // текст правила (для отображения)
        val explanation: String   // почему нарушено (от judge'а, для feedback/отказа)
    ) : InvariantResult()
}
```

## Проверка
- Unit-тесты в `InvariantTest` (задача 11): `Valid` singleton, `Violated` data-class equality.
- Зеркало `LlmResult<T>` / `AgentResult` — единый стиль Result'ов в проекте.

## Решения
- `Valid` — `object` (один экземпляр, нарушений нет → нет данных).
- `Violated` — `data class` (нужно сравнение по полям в тестах; explanation нужен и для отказа
  пользователю, и для feedback-промпта в retry-loop).
