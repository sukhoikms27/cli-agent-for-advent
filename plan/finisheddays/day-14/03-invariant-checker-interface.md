# Задача 03. Интерфейс `InvariantChecker`

## Что
Интерфейс проверки инвариантов. Точка абстракции курса: «интерфейс/абстрактный класс с функцией
`check`» (`03-memory-state-notes.md:373`). Позволяет менять стратегию (LLM-judge ↔ keyword ↔
композиция) без правок потребителей.

## Зависимости
01 (`Invariant`), 02 (`InvariantResult`).

## Реализация
Новый файл `src/main/kotlin/com/cliagent/state/invariant/InvariantChecker.kt`:

```kotlin
package com.cliagent.state.invariant

/**
 * Программная проверка инвариантов (день 14, третий столп недели 3).
 *
 * Defense-in-depth вместе с [com.cliagent.agent.PromptBuilder] (инварианты в промпте):
 * текстовые правила могут потеряться после сжатия истории → нужна кодовая проверка.
 *
 * Два направления проверки (см. `03-memory-state-notes.md:375-381` — «response фильтруется по
 * инвариантам»; расширено запросом по требованию day14 «отказ при конфликте запрос↔инвариант»):
 *  - [checkRequest]  — проверка запроса пользователя ДО основного LLM-вызова.
 *                      Violated → агент отказывает без генерации.
 *  - [checkResponse] — проверка ответа модели ПОСЛЕ LLM-вызова.
 *                      Violated → retry-loop с feedback.
 *
 * Разделение методов даёт разный UX (отказ vs. переделка) и разные judge-промпты.
 */
interface InvariantChecker {
    suspend fun checkRequest(text: String, invariants: List<Invariant>): InvariantResult
    suspend fun checkResponse(text: String, invariants: List<Invariant>): InvariantResult
}
```

## Проверка
- Compile-only на этом этапе (реализация — задача 04).
- В тестах `LlmInvariantCheckerTest` (задача 11) мокается через этот интерфейс.

## Решения
- Два метода, а не один `check(text, direction)` — явнее в точке вызова (`InvariantGuard`), и
  позволяет разные judge-промпты (запрос «хочет ли пользователь нарушить?» vs. ответ «нарушает ли
  предложенное решение?»). Один общий метод скрывал бы эту разницу.
- `suspend` — реализация LLM-judge ходит в сеть (конвенция CLAUDE.md: suspend для IO).
- `invariants` параметром (а не полем checker'а) — checker stateless, инварианты меняются во
  время сессии через `/invariants add/remove`.
