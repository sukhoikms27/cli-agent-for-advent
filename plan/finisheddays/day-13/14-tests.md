# Задача 14 (доработка Day 13). Тесты

## Цель
Покрыть stage-промпты, gate (canAdvance), эволюцию схемы, инъекцию stage-промпта, рендер артефактов.

## Тест-классы (`src/test/kotlin/com/cliagent/...`)

### 1. `llm/model/StagePromptTemplatesTest.kt` (новый)
- `buildSystemMessage` — непустой `ChatMessage` (role=system) для каждой из 5 стадий.
- Контент стадий различается (clarify ≠ execution ≠ validation ≠ planning ≠ done).
- Маркер стадии присутствует (clarify → «question»/«clarif»; execution → «implement»/«code»).

### 2. `state/TaskStateMachineTest.kt` (расширить) — canAdvance
- `CLARIFY` → true; `DONE` → false.
- `PLANNING` без plan → false; с `approvedPlan="x"` → true.
- `EXECUTION` без impl → false; с `implementation="x"` → true.
- `VALIDATION` без verdict → false; с `verdict="x"` → true.

### 3. `memory/ChatDataSchemaEvolutionTest.kt` (расширить)
- Legacy TaskState JSON без `implementation`/`verdict` → оба null.
- Round-trip `TaskState(stage=EXECUTION, implementation="x", verdict="y")` сохраняет поля.

### 4. `agent/ContextAwareAgentStagePromptTest.kt` (новый, по образцу `ContextAwareAgentProfileTest`)
- `taskState != null` (stage=CLARIFY) → `slot<ChatRequest>`, system content содержит маркер
  clarify-промпта и НЕ содержит дефолтного base.
- `taskState == null` → system content = Day 13 (base/`reasoningStrategy`).

### 5. `agent/PromptBuilderTest.kt` (расширить)
- `implementation`/`verdict` рендерятся (`Implementation:` / `Verdict:`).

## Критерии готовности
- `./gradlew test` — все зелёные (включая Day 11/12/13).

## Зависимости
Задачи 12, 13.
