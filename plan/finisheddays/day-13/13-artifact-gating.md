# Задача 13 (доработка Day 13). Artifact-gated переходы + артефакты стадий

## Контекст
Доработка Day 13 по комментариям автора курса (Вариант 2: «родился артефакт — поехали дальше»).
Детерминированный gate: стадия переводится вперёд только когда готов её артефакт. Этапы этой
задачи = декомпозиционные шаги 02–07 плана.

## Изменения (несколько файлов)

### 02. `state/TaskState.kt` — артефакт-поля
Добавить (после `approvedPlan`):
```kotlin
val approvedPlan: String? = null,      // артефакт planning (Day 13)
val implementation: String? = null,    // артефакт execution (доработка)
val verdict: String? = null,           // артефакт validation (доработка)
val stageHistory: List<StageTransition> = emptyList()
```
Поля с дефолтами → старый JSON грузится с null.

### 03. `state/TaskStateMachine.kt` — canAdvance
```kotlin
fun canAdvance(state: TaskState): Boolean = when (state.stage) {
    TaskStage.CLARIFY -> true   // опц. стадия
    TaskStage.PLANNING -> !state.approvedPlan.isNullOrBlank()
    TaskStage.EXECUTION -> !state.implementation.isNullOrBlank()
    TaskStage.VALIDATION -> !state.verdict.isNullOrBlank()
    TaskStage.DONE -> false
}
```
Артефакты НЕ reset’ятся при переходе. `transition`/`forceSet`/`back` без изменений.

### 04. `agent/PromptBuilder.kt` — рендер артефактов
В `WorkingMemory.renderBlock()` (после `approvedPlan`):
```kotlin
ts.implementation?.let { lines.add("  Implementation: $it") }
ts.verdict?.let { lines.add("  Verdict: $it") }
```

### 05. `agent/ContextAwareAgent.kt` — stage-prompt precedence
В `buildMessagesToSend`:
```kotlin
val taskState = getTaskState()
val baseSystem = when {
    taskState != null -> StagePromptTemplates.buildSystemMessage(taskState.stage)
    reasoningStrategy != null -> PromptTemplates.buildSystemMessage(reasoningStrategy)
    else -> systemPrompt
}
```
Инвариант: `taskState == null` → поведение Day 13.

### 06. `cli/ChatCommand.kt` — gating + команды
- `/task next`: hard-block по `canAdvance` (warn + return если артефакт не готов); hint per stage.
- `/task impl <text>` → `setTaskState(cur.copy(implementation = text))`.
- `/task verdict <text>` → `setTaskState(cur.copy(verdict = text))`.
- `/task show` — `Implementation:` / `Verdict:`.
- `printHelp` — `impl`/`verdict` + заметка про gating; версия `v0.7`.
- `/task set` (force) — escape hatch, без изменений.

### 07. `cli/ReplEngine.kt` — completion
В `ArgumentCompleter` для `/task` добавить `impl`, `verdict`.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `/task next` из PLANNING без plan → warn, стадия НЕ меняется; с plan → execution.
- `/task impl`/`/task verdict` обновляют артефакты; `/task show` показывает.
- `/task set execution` из planning → force (минуя gate).
- `taskState != null` → stage-промпт в system content; `taskState == null` → Day 13 base.

## Зависимости
Задача 12 (StagePromptTemplates).
