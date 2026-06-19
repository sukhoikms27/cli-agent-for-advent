# Задача 09. Тесты

## Цель
Покрыть стейт-машину, эволюцию схемы, аксессоры агента и рендер в промпт.

## Тест-классы (`src/test/kotlin/com/cliagent/...`)

### 1. `state/TaskStateMachineTest.kt` (новый, чистые unit-тесты без моков)
- `isAllowed` true для всех 7 пар + self-pairs.
- `isAllowed` false для запрещённых (`PLANNING→DONE`, `DONE→EXECUTION`, `VALIDATION→PLANNING`,
  `CLARIFY→EXECUTION`).
- `next()`: `CLARIFY→PLANNING`, `PLANNING→EXECUTION`, `EXECUTION→VALIDATION`,
  `VALIDATION→DONE`, `DONE→null`.
- `transition` обновляет stage и дописывает `StageTransition` (from/to/note).
- `transition(PLANNING, DONE)` кидает `IllegalArgumentException`.
- `transition` self-transition не добавляет history.
- `forceSet(PLANNING, DONE)` проходит, `note="forced"`; self-transition — no-op.
- `back` восстанавливает `from` последней записи и удаляет её; `null` на пустой history.

### 2. `memory/ChatDataSchemaEvolutionTest.kt` (расширить)
- Legacy `WorkingMemory` JSON без `taskState` → `taskState == null`.
- Round-trip `WorkingMemory(currentTask="x", taskState=TaskState(stage=EXECUTION,
  currentStep="s"))` сохраняет оба.
- Образец — `pre-Day-11 chat JSON without workingMemory loads with null`.

### 3. `agent/ContextAwareAgentTaskStateTest.kt` (новый)
Переиспользовать `fakeResponse` из `ContextAwareAgentProfileTest`; `storeMock` расширить моками
`saveWorkingMemory`/`loadWorkingMemory` (через captured slot, эмулируя персистентность).
- `setTaskState` персистит и читается.
- `setTaskState` НЕ затирает `currentTask`/`plan`/`taskDecisions` (аналог
  `setProfile preserves long-term knowledge and decisions`).
- `setTaskState(null)` чистит task state, сохраняя остальную WorkingMemory.
- `advanceTaskState` проходит канонические стадии; `null` из DONE.
- `revertTaskState` откатывает по history; `null` на пустой.
- (opt) состояние инжектится в system prompt: `setTaskState` → `chat` → `slot<ChatRequest>` →
  `content` содержит `Task state:` и `Stage: execution`.

### 4. `agent/PromptBuilderTest.kt` (расширить, по образцу Day 12 profile-render)
- `taskState` рендерит блок (`Task state:`, `Stage: execution`, `Current step:`,
  `Expected action:`).
- `approvedPlan` рендерится когда задан.
- `taskState == null` не рендерит `Task state:`.

## Критерии готовности
- `./gradlew test` — все зелёные (включая Day 11/12).

## Зависимости
Задачи 03, 05, 06.
