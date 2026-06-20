# Задача 20. Оркестратор mode-aware: MANUAL/PLAN/AUTO (п.3)

## Цель
Оркестратор и `ChatCommand` уважают `InteractionMode`:
- **MANUAL** — свободный текст = обычный чат; FSM только через явные `/task`-команды. Авто-роутинг
  интента отключён.
- **PLAN** — текущее поведение (подтверждение каждого перехода; авто-роутинг активен).
- **AUTO** — переходы без подтверждения (авто-advance после готовности артефакта; авто-роутинг активен).

## Зависимости
03 (роутинг), 18 (`InteractionMode`), 17 (`StageStepResult`/announcer), 09 (`attemptTransition`).

## Файлы (правка)
1. `src/main/kotlin/com/cliagent/agent/stage/TaskOrchestrator.kt` — режим-aware логика.
2. `src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — else-ветка с учётом режима.

## Что изменить

### 1. `ChatCommand` else-ветка — полная логика с режимами (объединяет задачи 03 + 20)

```kotlin
else -> {
    val base = agent.contextAware
    val taskState = base.getTaskState()
    val mode = base.getWorkingMemory()?.interactionMode ?: InteractionMode.PLAN

    // MANUAL: свободный текст = обычный чат (FSM только через /task). Авто-роутинг отключён.
    if (mode == InteractionMode.MANUAL && taskState == null) {
        val response = AppTerminal.withSpinner({ spinnerLabel(agent) }) { agent.chat(input) }
        AppTerminal.println()
        AppTerminal.markdown(response)
        AppTerminal.println()
        return@runBlocking
    }

    // День 15 (п.1): при отсутствии активной задачи (режим ≠ MANUAL) — авто-определение интента.
    if (taskState == null) {
        val intent = intentClassifier.classify(input)
        if (intent == UserIntent.TASK) {
            val w = base.getWorkingMemory() ?: WorkingMemory()
            base.setWorkingMemory(w.copy(currentTask = input))
            val display = AppTerminal.withSpinner({ spinnerLabel(agent) }) {
                orchestrator.startTask(input)
            }
            AppTerminal.println()
            renderStageResult(display)   // через StageAnnouncer (задача 17)
            AppTerminal.println()
            return@runBlocking
        }
        // QUESTION → обычный чат
    }

    // День 13 (авто-поток): при активной задаче свободный текст = подтверждение/уточнение.
    val stepResult = AppTerminal.withSpinner({ spinnerLabel(agent) }) {
        orchestrator.handleUserInput(input, mode)   // режим передаётся в оркестратор
    }
    if (stepResult != null) {
        AppTerminal.println()
        renderStageResult(stepResult)
        AppTerminal.println()
    } else {
        // нет активной задачи и QUESTION → обычный чат
        val response = AppTerminal.withSpinner({ spinnerLabel(agent) }) { agent.chat(input) }
        AppTerminal.println()
        AppTerminal.markdown(response)
        AppTerminal.println()
    }
}
```

### 2. `TaskOrchestrator.handleUserInput` — режим-aware

**Сейчас** (строки 71–86): решает по `awaitingAdvance` — подтверждение vs уточнение.

**После:** принимает `mode` параметр; в AUTO — авто-advance без ожидания подтверждения:

```kotlin
suspend fun handleUserInput(text: String, mode: InteractionMode = InteractionMode.PLAN): StageStepResult? {
    val state = agent.getTaskState() ?: return null
    val desc = state.currentStep ?: ""

    return if (mode == InteractionMode.AUTO && state.awaitingAdvance) {
        // AUTO: не ждём «да» — авто-advance (если артефакт готов; guard проверит)
        advanceAndRunNext(state, desc)
    } else if (state.awaitingAdvance) {
        // PLAN/MANUAL: подтверждение или уточнение
        if (isYes(text)) {
            advanceAndRunNext(state, desc)
        } else {
            runStageAndDisplay(state.stage, desc, feedback = text)
        }
    } else {
        // awaitingAdvance=false → clarify ждёт ответы (все режимы)
        runStageAndDisplay(state.stage, desc, feedback = text)
    }
}
```

### 3. AUTO — авто-advance после генерации артефакта (в `runStageAndDisplay`)

В AUTO-режиме после `result.readyToAdvance = true` оркестратор НЕ выставляет `awaitingAdvance = true`
для запроса подтверждения, а сразу продолжает к следующей стадии (рекурсивно `advanceAndRunNext`),
пока не дойдёт до DONE или блокировки. Это «полная автоматизация».

Реализация: добавить проверку режима в `runStageAndDisplay` (или в `handleUserInput` после первого
`runStageAndDisplay`). Осторожно с бесконечной рекурсией — ограничить глубиной (5 стадий) или
явной проверкой `stage == DONE`.

```kotlin
// В runStageAndDisplay, после storeArtifact + если AUTO и readyToAdvance:
if (mode == InteractionMode.AUTO && result.readyToAdvance && stage != TaskStage.DONE) {
    val next = TaskStateMachine.next(stage) ?: return currentResult
    val updated = agent.getTaskState()!!
    // авто-advance (guard внутри advanceAndRunNext)
    return advanceAndRunNext(updated, taskDescription)  // рекурсивно к следующей стадии
}
```

> Внимание: рекурсия ограничена количеством стадий (макс 5: clarify→planning→execution→validation→
> done). При блокировке (ArtifactMissing/Illegal) advanceAndRunNext возвращает blocked — рекурсия
> останавливается. Бесконечного цикла нет (конечный DAG стадий).

## Рендеринг в AUTO — уведомления без запроса

В AUTO `StageAnnouncer.stageBlockWithPrompt` опускает «Перейти к …?» (нечего подтверждать).
Реализация (задача 17): добавить параметр `promptForConfirmation: Boolean` или отдельный метод
`stageBlockAuto`:
```kotlin
fun stageBlock(stage: TaskStage, display: String, readyToAdvance: Boolean, mode: InteractionMode): String =
    // ... если AUTO и readyToAdvance — добавить "⏭ → <next>" без вопроса
```

## Логика по режимам (сводная таблица)

| Ситуация | MANUAL | PLAN | AUTO |
|---|---|---|---|
| Нет задачи, ввод | чат (роутинг off) | IntentClassifier (Q→чат, T→старт) | IntentClassifier (Q→чат, T→старт) |
| Активная задача, `awaitingAdvance=true`, ввод «да» | advance | advance | advance (но и без «да») |
| Активная задача, `awaitingAdvance=true`, иной ввод | уточнение | уточнение | advance (игнор текста) |
| Артефакт готов после генерации | выставить `awaitingAdvance` | выставить `awaitingAdvance` | авто-advance к след. стадии |
| Переход нелегальный/без артефакта | ⛔ blocked (guard) | ⛔ blocked (guard) | ⛔ blocked (guard) |

## Ключевые инварианты
- **`TransitionGuard` соблюдается ВО ВСЕХ режимах** — AUTO автоматизирует подтверждение, НЕ обход
  проверок. Перепрыгивание блокируется везде (ядро задания дня 15). `attemptTransition` — единая
  точка, режим на неё не влияет.
- **MANUAL = авто-роутинг off** — пользователь хочет полный контроль; FSM только через `/task`.
  Свободный текст всегда чат (если нет активной задачи).
- **AUTO = авто-advance после готовности** — но с уведомлениями (`StageAnnouncer`); пользователь
  видит прогресс, хоть и не подтверждает. Иначе «чёрный ящик».
- **PLAN = текущее поведение** — default; нулевая регрессия.
- **`awaitingAdvance` в AUTO** — может игнорироваться (auto-advance не ждёт), но поле сохраняется
  для совместимости (если переключиться в PLAN mid-task — корректное состояние).

## Решения
- **`mode` параметр в `handleUserInput`** — режим передаётся явно из `ChatCommand` (который читает
  `WorkingMemory`). Оркестратор не лезет в `WorkingMemory` напрямую (он работает с `agent`
  аксессорами состояния). Альтернатива — оркестратор сам читает режим через `agent.getWorkingMemory`
  — допустимо, но явный параметр чище (тестируемость: mock mode в тестах).
- **Default `PLAN`** — обратно совместимо; существующие вызовы `handleUserInput(text)` работают.
- **Рекурсия в AUTO ограничена DAG стадий** — максимум 5 переходов; блокировка останавливает. Нет
  риска stack overflow.
- **`clarify` ждёт ответы во ВСЕХ режимах** — `awaitingAdvance=false` (clarify-стадия) всегда
  обрабатывает текст как feedback (уточнение требований). Это правильно: clarify по природе
  интерактивен, авто-advance возможен только при `[CLEAR]` (особый случай, строки 144–150).

## Критерии готовности
- **MANUAL**: свободный текст без задачи → чат; `/task start` работает; роутинг off.
- **PLAN**: поведение идентично дню 13 (подтверждение переходов); роутинг on.
- **AUTO**: TASK-ввод → автостарт → авто-проход до DONE или блокировки, с уведомлениями, без
  запросов «да».
- Во всех режимах нелегальный переход → `⛔ blocked` (guard).
- `handleUserInput(text, mode)` — default `PLAN` (обратная совместимость).

## Зависимости (задачи)
03, 09, 17, 18, 19. Демо D в 25.
