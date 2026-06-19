# Задача 08. Decorator `InvariantGuard`

## Что
Обёртка поверх `Agent`, реализующая программную проверку инвариантов (второй слой
defense-in-depth) в обоих направлениях: запрос → отказ-без-LLM; ответ → retry-loop. Центральный
компонент дня.

## Зависимости
03 (`InvariantChecker`), 01 (`Invariant`), 02 (`InvariantResult`). Реализует `Agent` (interface).
Нужен `ContextAwareAgent.getInvariants()` (задача 07) — но через абстракцию, не хардкод.

## Реализация
Новый файл `src/main/kotlin/com/cliagent/agent/InvariantGuard.kt`:

```kotlin
/**
 * Decorator поверх [Agent], обеспечивающий соблюдение инвариантов (день 14).
 *
 * - checkRequest: запрос-нарушитель → отказ БЕЗ основного LLM-вызова (с объяснением, какой rule
 *   нарушен — требование day14 «объясняет отказ»).
 * - checkResponse: ответ-нарушитель → retry-loop с feedback (max [MAX_RETRIES]) → fallback с ⚠️.
 *
 * Конвенция CLAUDE.md: decorator не меняет проверенный ContextAwareAgent; checker через абстракцию;
 * suspend для IO.
 */
class InvariantGuard(
    private val delegated: Agent,
    private val checker: InvariantChecker,
    private val invariantsProvider: suspend () -> List<Invariant>  // getInvariants() из агента
) : Agent {

    override suspend fun chat(userMessage: String): String {
        val invariants = invariantsProvider()
        if (invariants.isEmpty()) return delegated.chat(userMessage)  // fast-path: нечего проверять

        // 1. Проверка ЗАПРОСА — отказ без LLM
        val reqResult = checker.checkRequest(userMessage, invariants)
        if (reqResult is InvariantResult.Violated) {
            return refuseMessage(reqResult)  // ⛔ без delegated.chat
        }

        // 2. Генерация + проверка ОТВЕТА — retry-loop
        var response = delegated.chat(userMessage)
        var violations = 0
        while (violations < MAX_RETRIES) {
            val respResult = checker.checkResponse(response, invariants)
            if (respResult is InvariantResult.Valid) return response
            violations++
            response = delegated.chat(feedbackMessage(respResult, userMessage))
        }
        // 3. Fallback — не удалось соблюсти за MAX_RETRIES
        return fallbackMessage(response)
    }

    private fun refuseMessage(v: InvariantResult.Violated): String =
        "⛔ Не могу выполнить этот запрос: он нарушает инвариант проекта.\n" +
        "   Правило: ${v.rule}\n   Причина: ${v.explanation}\n" +
        "   Переформулируйте запрос в рамках заданных ограничений."

    private fun feedbackMessage(v: InvariantResult.Violated, originalRequest: String): String =
        "Твой предыдущий ответ нарушает инвариант проекта: «${v.rule}» (${v.explanation}). " +
        "Переделай ответ, строго соблюдая это правило. Исходный запрос: $originalRequest"

    private fun fallbackMessage(lastResponse: String): String =
        "⚠️ Внимание: не удалось полностью соблюсти инварианты проекта за $MAX_RETRIES попыток.\n" +
        "Последний ответ (требует ручной проверки):\n\n$lastResponse"

    override suspend fun getHistory(): List<ChatMessage> = delegated.getHistory()
    override suspend fun reset() { delegated.reset() }

    private companion object { const val MAX_RETRIES = 3 }
}
```

### `invariantsProvider`
Лямбда-провайдер вместо прямой ссылки на `ContextAwareAgent.getInvariants` — развязка: guard не
знает конкретный класс агента (только интерфейс `Agent`), а инварианты достаёт через колбэк.
В `ChatCommand`: `InvariantGuard(base, checker) { base.getInvariants() }`.

## Поток (детально)
```
chat(msg):
  invariants = provider()
  пусто → delegated.chat(msg)                              [fast-path, ноль накладных]
  checkRequest(msg)
    Violated → refuseMessage (БЕЗ LLM)                    [отказ с объяснением]
    Valid → response = delegated.chat(msg)
              loop (×3):
                checkResponse(response)
                  Valid → return response                 [счастливый путь]
                  Violated → response = delegated.chat(feedback)   [retry]
              → fallbackMessage(response)                  [не уложились]
```

## Проверка (задача 11, `InvariantGuardTest`)
- mock `delegated` (coEvery возвращает ответы) + mock `checker`.
- Пустые invariants → `delegated.chat` вызван 1 раз, checker не звали (fast-path).
- `checkRequest` Violated → `delegated.chat` **не вызван** (verifyZeroInteractions), возврат
  содержит `⛔` + rule + explanation.
- `checkResponse` Valid → 1 вызов delegated, возврат = ответ.
- `checkResponse` Violated всегда → 1 + 3 = 4 вызова delegated (initial + 3 retry), возврат =
  fallback с `⚠️`.
- `checkResponse` Violated→Valid (на 2-й раз) → 2 вызова delegated, возврат = второй ответ.
- `MAX_RETRIES` = 3 — assert не больше 4 delegated-вызовов (защита от бесконечного цикла).

## Решения
- **Decorator, не правка ContextAwareAgent.chat()** — Open-Closed: ContextAwareAgent и его 61
  тест не трогаются; guard тестируется изолированно (mock делегата).
- **`invariantsProvider` лямбда** — guard зависит от `Agent` + `InvariantChecker`, не от
  конкретного `ContextAwareAgent` (можно обернуть любого агента).
- **retry = 3** — баланс: достаточно для самокоррекции модели, не слишком дорого по токенам.
- **feedback-промпт** — даёт модели конкретику (какой rule, explanation, исходный запрос) —
  максимизирует шанс исправления за минимум попыток.
- **fallback не прячет нарушение** — показывает `⚠️` и последний ответ; пользователь предупреждён.
