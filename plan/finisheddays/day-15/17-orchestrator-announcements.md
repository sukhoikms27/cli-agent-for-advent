# Задача 17. Уведомления о стадиях в оркестраторе + рендеринг (п.2)

## Цель
Подключить `StageAnnouncer` к stage-потоку: `runStageAndDisplay` формирует структурные уведомления;
`ChatCommand` рендерит их через announcer. Особенно важно для AUTO-режима (без подтверждения).

## Зависимости
16 (`StageAnnouncer`). Существующий `TaskOrchestrator.runStageAndDisplay` (строки 116–161) +
`advanceAndRunNext` (98–110).

## Файлы (правка)
1. `src/main/kotlin/com/cliagent/agent/stage/TaskOrchestrator.kt` — `runStageAndDisplay`,
   `advanceAndRunNext`.
2. `src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — рендеринг ответов оркестратора.

## Что изменить

### 1. `TaskOrchestrator.runStageAndDisplay` (строки 152–158) — делегирование форматирования в announcer

**Сейчас** (хардкоженый prompt-блок):
```kotlin
val prompt = if (result.readyToAdvance && stage != TaskStage.DONE) {
    val nextStage = TaskStateMachine.next(stage)
    if (nextStage != null) {
        "\n\n___\n\n✅ Перейти к стадии **${nextStage.name}**? " +
            "Ответь **да** — продолжить, либо напиши уточнение для доработки."
    } else null
} else null

return result.display + (prompt ?: "")
```

**После:** хардкоженый prompt заменяется на `StageAnnouncer.stageBlockWithPrompt`. Но оркестратор не
должен зависеть от CLI-слоя (`cli.StageAnnouncer`) — это нарушило бы слоистость (`agent/stage → cli`).

**Решение:** вынести форматирование уведомлений в колбэк-инжект или вернуть структурированный
результат. Минимально-инвазивный путь — **вернуть расширенный результат** с метаданными, а
`ChatCommand` форматирует через `StageAnnouncer`:

Добавить в `TaskOrchestrator` data class результата (или расширить возврат):
```kotlin
/**
 * Результат stage-шага с метаданными для уведомлений (день 15, п.2).
 * ChatCommand рендерит через StageAnnouncer.
 */
data class StageStepResult(
    val stage: TaskStage,
    val display: String,              // содержимое артефакта (StageResult.display)
    val readyToAdvance: Boolean,      // готовность к переходу
    val nextStage: TaskStage? = null, // канонический следующий (null если DONE/нет)
    val blocked: String? = null       // причина блокировки перехода (TransitionOutcome), null если ок
)
```

И `runStageAndDisplay` возвращает `StageStepResult` вместо голой `String`:
```kotlin
private suspend fun runStageAndDisplay(
    stage: TaskStage,
    taskDescription: String,
    feedback: String?
): StageStepResult {
    // ... существующая логика до result.display ...
    val nextStage = TaskStateMachine.next(stage)
    return StageStepResult(
        stage = stage,
        display = result.display,
        readyToAdvance = result.readyToAdvance,
        nextStage = nextStage.takeIf { stage != TaskStage.DONE }
    )
}
```

`handleUserInput`/`startTask` (публичные) тоже возвращают `StageStepResult` (или wrapper). Особый
случай CLARIFY→PLANNING auto-advance (строки 144–150) возвращает комбинированный результат.

### 2. `advanceAndRunNext` (строки 98–110) — результат с blocked

**Сейчас:** возвращает `String` (markdown). При `!canAdvance` — строка с `⚠️`.

**После:** возвращает `StageStepResult` с `blocked` полем, заполненным из `TransitionOutcome`:
```kotlin
private suspend fun advanceAndRunNext(state: TaskState, desc: String): StageStepResult {
    if (state.stage == TaskStage.DONE) {
        return StageStepResult(TaskStage.DONE, "🏁 Задача уже завершена.", true)
    }
    // День 15: переход через TransitionGuard (единая точка)
    val nextStage = TaskStateMachine.next(state.stage)
        ?: return StageStepResult(state.stage, "✓ Уже на финальной стадии.", true)
    val outcome = agent.attemptTransition(nextStage)  // guard вместо ручного canAdvance
    return when (outcome) {
        is TransitionOutcome.Allowed ->
            runStageAndDisplay(nextStage, desc, feedback = null)
        is TransitionOutcome.ArtifactMissing ->
            StageStepResult(state.stage, "", readyToAdvance = false,
                blocked = "Стадия ${state.stage.name.lowercase()} не готова: ${outcome.hint}")
        is TransitionOutcome.Illegal ->
            StageStepResult(state.stage, "", readyToAdvance = false,
                blocked = "Illegal transition ${outcome.from}→${outcome.to}")
        null -> StageStepResult(state.stage, "Нет активной задачи.", true)
    }
}
```

### 3. `ChatCommand` — рендеринг `StageStepResult` через `StageAnnouncer`

**Сейчас** (строки 157–163): `AppTerminal.markdown(taskResponse)` где `taskResponse: String`.

**После:** `taskResponse: StageStepResult`; рендеринг:
```kotlin
val stepResult = AppTerminal.withSpinner({ spinnerLabel(agent) }) {
    orchestrator.handleUserInput(input)
}
if (stepResult != null) {
    AppTerminal.println()
    if (stepResult.blocked != null) {
        AppTerminal.warn(StageAnnouncer.blocked(stepResult.blocked))
    } else {
        AppTerminal.markdown(
            StageAnnouncer.stageBlockWithPrompt(
                stepResult.stage, stepResult.display,
                stepResult.readyToAdvance, stepResult.nextStage
            )
        )
    }
    AppTerminal.println()
}
```

Аналогично для `startTask` (строки 800–804) и авто-роутинга TASK (задача 03).

## Совместимость с AUTO-режимом (задача 20)
В AUTO-режиме оркестратор НЕ запрашивает подтверждение (`awaitingAdvance` игнорируется, авто-advance).
Но уведомления `StageAnnouncer` показываются ВСЕГДА — пользователь видит прогресс каждой стадии, даже
если не подтверждает переходы. `stageBlockWithPrompt` в AUTO может опускать «Перейти к …?» (нет нужды
спрашивать) — это обрабатывается в задаче 20 (передача режима в рендеринг или отдельный метод
`stageBlockAuto`).

## Ключевые инварианты
- **Слоистость сохранена** — оркестратор возвращает структурированные данные (`StageStepResult`), не
  markdown. CLI (`ChatCommand`) форматирует через `StageAnnouncer`. `agent/stage → cli` зависимости НЕТ.
- **`StageResult.display` не дублируется** — announcer оборачивает его структурными префиксами, не
  подменяет содержание артефакта.
- **`blocked` из `TransitionOutcome`** — машиночитаемая причина блокировки доходит до пользователя
  через announcer (раньше оркестратор сам формулировал строку с `⚠️`).
- **Особый случай CLARIFY→PLANNING** — сохраняется (строки 144–150), но возвращает комбинированный
  `StageStepResult` (clarify-часть + planning-часть через `---`).

## Решения
- **`StageStepResult` data class** — расширение возврата оркестратора. Альтернатива — колбэк-инжект
  (`onStage: (StageStepResult) -> Unit`) — отвергнута: сложнее тестировать, нарушает «функция
  возвращает результат». Data class — явный, тестируемый.
- **`blocked` как `String?`** — упрощение; полный `TransitionOutcome` не нужен оркестратору для
  рендеринга (он уже отработал guard). Достаточно человекочитаемой причины.
- **`handleUserInput`/`startTask` сигнатуры меняются** — публичный API. Это breaking change для
  существующих тестов оркестратора (если есть) — проверить и обновить. `ChatCommand` — единственный
  потребитель, обновляется в этой задаче.

## Критерии готовности
- `TaskOrchestrator.runStageAndDisplay`/`advanceAndRunNext` возвращают `StageStepResult`.
- `ChatCommand` рендерит через `StageAnnouncer.stageBlockWithPrompt`/`.blocked`.
- Хардкоженый prompt-блок оркестратора (строки 152–158) удалён.
- `TransitionOutcome` используется в `advanceAndRunNext` (единая точка перехода).
- Уведомления видны в stage-потоке: `📋 Планирование` / `📦 Утверждённый план готов` / `⏭ → Реализация`.
- Существующее поведение PLAN-режима (запрос «да» для перехода) сохранено.

## Зависимости (задачи)
16. Согласуется с 09 (`attemptTransition`), 20 (AUTO-режим). Демо E в 25.
