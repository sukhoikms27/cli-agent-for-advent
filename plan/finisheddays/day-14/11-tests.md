# Задача 11. Тесты (4 файла)

## Что
Юнит-тесты на все новые компоненты + расширение schema-evolution. Стек: JUnit 5 + MockK +
`runTest` + `@TempDir` (конвенции CLAUDE.md).

## Зависимости
01–09 (тестируют реализацию этих задач).

## Реализация
Новые/расширенные файлы в `src/test/kotlin/com/cliagent/`.

### 11.1 `state/invariant/InvariantTest.kt` (новый)
- `Invariant` round-trip JSON через `AppJson` (encode → decode → equals).
- `InvariantCategory` — все 4 значения сериализуются.
- `InvariantResult.Valid` — singleton equality (`Valid == Valid`).
- `InvariantResult.Violated` — data-class equality по полям.

### 11.2 `state/invariant/LlmInvariantCheckerTest.kt` (новый)
Паттерн: mock `LlmClient`, `coEvery { chat(capture(slot)) }` → `LlmResult.Success(fakeResponse)`.
- `{"violated":true,"ruleId":"no-compose","explanation":"содержит setContent{}"}` → `Violated` с
  корректными `ruleId/rule/explanation` (rule достаётся из списка invariants по id).
- `{"violated":false,...}` → `Valid`.
- `LlmResult.Error(500,...)` → **`Valid`** (fallback-safe, не блокируем).
- Пустой список invariants → `Valid` **без** вызова `llmClient.chat` (`coVerify { llmClient wasNot Called }`).
- Мусор/не-JSON ответ → `Valid` (tolerant парсинг).
- Захваченный `ChatRequest.temperature == 0.0` (детерминированность).
- `checkRequest` промпт содержит «запрос/хочет»-формулировку; `checkResponse` — «ответ/содержит»
  (assert на захваченном `slot.captured.messages.last().content`).
- Markdown-обёртка ```json {...} ``` корректно снимается парсером.

### 11.3 `agent/InvariantGuardTest.kt` (новый)
Паттерн: mock `delegated: Agent` (`coEvery { chat(any()) } returnsSequence ...`), mock `checker`.
- Пустые invariants → `delegated.chat` 1 раз, `checker` не звали (fast-path).
- `checkRequest` → `Violated` → `delegated.chat` **не вызван** (`coVerify(exactly=0)`), возврат
  содержит `⛔` + rule + explanation.
- `checkRequest` Valid + `checkResponse` Valid → `delegated.chat` ровно 1 раз, возврат = ответ.
- `checkResponse` всегда `Violated` → `delegated.chat` 4 раза (initial + 3 retry), возврат =
  fallback с `⚠️` и «MAX_RETRIES».
- `checkResponse` Violated→Valid (на 2-й раз, через `returnsChained`) → `delegated.chat` 2 раза,
  возврат = второй ответ.
- `getHistory`/`reset` делегируются (`coVerify { delegated.getHistory() }`).

### 11.4 `memory/ChatDataSchemaEvolutionTest.kt` (расширение, +2-3 кейса)
- Legacy `LongTermMemory` JSON без `invariants` → грузится, `invariants == emptyList()`.
- Round-trip `LongTermMemory(invariants = listOf(Invariant("no-compose","...",BAN)))`.
- `LongTermMemory(...).isEmpty()` = false когда есть инвариант (даже без profile/knowledge).

### 11.5 `agent/PromptBuilderTest.kt` (расширение, +2 кейса)
- `LongTermMemory(invariants=[...])` → `build().content` содержит `[Project invariants]`, текст
  правила, `MUST NOT`.
- `LongTermMemory()` (пустой) / `null` → блок отсутствует (ноль регресса day-13).

### 11.6 `agent/ContextAwareAgentInvariantsTest.kt` (новый, по образцу Profile-тестов)
- `setInvariants/addInvariant/removeInvariant` round-trip через MemoryStore mock.
- `addInvariant` с тем же id — обновляет, не дублирует.
- `removeInvariant("zzz")` → false; `removeInvariant("a")` → true.
- Не затирает profile/knowledge (assert после setInvariants).

## Проверка (мета)
- `./gradlew test` → все новые зелёные + **61 существующий не падает** (нулевая регрессия —
  `--invariants` default off, поле с дефолтом, guard через decorator).
- Coverage: каждая новая public-функция имеет happy + edge/fallback кейс.

## Решения
- **Fallback-тесты обязательны** — поведение judge'а при ошибке (`Valid`) и guard'а при
  исчерпании retry (`⚠️` fallback) — критичные инварианты системы, не «nice to have».
- **Mock делегата в GuardTest** — guard тестируется изолированно от реального ContextAwareAgent
  (как и в day-13 stage-тестах). Это и есть ценность decorator-паттерна.
- **Schema-evolution первый** — если legacy JSON не грузится, всё остальное бессмысленно; этот
  тест — канарейка совместимости.
