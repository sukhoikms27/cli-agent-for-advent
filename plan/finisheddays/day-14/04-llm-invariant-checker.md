# Задача 04. Реализация `LlmInvariantChecker` (LLM-as-judge)

## Что
Единственная реализация `InvariantChecker` — семантическая проверка через LLM-as-judge.
Stateless-утилитарный класс по образцу `ProfileExtractor` (день 12): один короткий LLM-вызов →
парсинг JSON. **Не агент** (нет истории/контекста/стейта).

## Зависимости
03 (`InvariantChecker`), 01 (`Invariant`), 02 (`InvariantResult`).
Переиспользует: `LlmClient`, `ChatRequest`, `ChatMessage`, `LlmResult`, `AppJson` (kotlinx.serialization).

## Реализация
Новый файл `src/main/kotlin/com/cliagent/state/invariant/LlmInvariantChecker.kt`.

### Конструктор (зеркало `ProfileExtractor`)
```kotlin
class LlmInvariantChecker(
    private val llmClient: LlmClient,   // тот же client, что у основного агента
    private val model: String            // та же модель (glm-5.1) — решение пользователя
) : InvariantChecker
```

### Логика `checkResponse` / `checkRequest`
1. Если `invariants.isEmpty()` → сразу `Valid` (без LLM-вызова — экономия).
2. Judge-промпт:
   ```
   Ты — строгий ревьюер. Проверь, нарушает ли ТЕКСТ эти правила проекта:
   [invariant.id] invariant.rule
   ...
   ТЕКСТ: <text>
   Ответь СТРОГО JSON без markdown: {"violated": true/false, "ruleId": "...", "explanation": "..."}
   Если violated=false, ruleId и explanation — пустые строки.
   ```
   - `checkRequest` — формулировка «хочет ли пользователь получить решение, нарушающее правило?».
   - `checkResponse` — «содержит ли предложенный ответ нарушение правила?».
3. `ChatRequest(model, [ChatMessage(user, prompt)], temperature = 0.0)`.
4. `llmClient.chat(request)`:
   - `LlmResult.Success` → парсинг JSON: `violated=true` → `Violated(ruleId, rule, explanation)`;
     иначе `Valid`. `rule` достаётся из `invariants.first { it.id == ruleId }`.
   - `LlmResult.Error` → **fallback-safe: `Valid`** (не блокируем пользователя при сбое сети/судьи;
     лучше пропустить, чем зависнуть; см. риск #2 в README).
   - Неразборный JSON → fallback `Valid` + (опц.) лог warning.

### Парсинг JSON
`kotlinx.serialization.json.Json` — отдельный `parseToJsonElement` → `JsonObject["violated"]`.
Толерантный парсинг: модель может обернуть в markdown ```json ... ``` — снять обёртку.

## Проверка (юнит-тесты — задача 11, `LlmInvariantCheckerTest`)
- mock `LlmClient` → `{"violated":true,"ruleId":"no-compose","explanation":"..."}` → `Violated`.
- mock → `{"violated":false,...}` → `Valid`.
- `LlmResult.Error` → `Valid` (fallback-safe).
- пустой список invariants → `Valid` без вызова `llmClient.chat` (verifyZeroInteractions).
- мусор/не-JSON ответ → `Valid` (tolerant).
- `temperature == 0.0` в захваченном `ChatRequest` (slot).
- `checkRequest` vs `checkResponse` — разные judge-формулировки (захватить промпт, assert содержит).

## Решения
- **temperature=0** — детерминированность judge'а (минимизируем недетерминизм, риск #2).
- **fallback → Valid** — судья не должен блокировать пользователя при сбое; «false positive на
  валидный ответ» < «зависание из-за упавшего судьи».
- **stateless** — judge не помнит прошлые проверки; каждая — свежий вызов. Это сознательно: для
  проверки «нарушает ли текст правило» контекст предыдущих ходов — шум.
- **один общий `judge(prompt, text, invariants)` хелпер** внутри класса — DRY, разница только в
  формулировке prompt-преамбулы.
