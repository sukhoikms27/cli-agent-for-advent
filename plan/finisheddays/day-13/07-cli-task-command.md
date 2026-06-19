# Задача 07. /task CLI-команда

## Цель
Ручное управление состоянием задачи через slash-команды. По образцу `handleProfile`.

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt`

## Что изменить

### 1. Импорты
```kotlin
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.state.TaskStage
import com.cliagent.state.TaskState
import com.cliagent.state.TaskStateMachine
```

### 2. Диспетч (после `/profile`, строка ~119)
```kotlin
input.startsWith("/task") -> handleTask(input, agent)
```

### 3. `handleTask(input, agent)` — по образцу `handleProfile`
`parts = input.trim().split("\\s+".toRegex())`, `when (parts[1])`:
- `/task | /task show` — показать состояние или `No active task. Use: /task start <description>`.
- `/task start <description>` — `setWorkingMemory(w.copy(currentTask = desc, taskState =
  TaskState(stage = PLANNING, currentStep = desc)))` **одним вызовом** (без двойной персистентности).
- `/task next` — `agent.advanceTaskState()`; warn если null (DONE/нет задачи).
- `/task set <stage>` — `TaskStage.valueOf(parts[2].uppercase())` (case-insensitive, невалидное →
  warn); warn если `!isAllowed`, но применить через `forceSet`.
- `/task step <text>` — `setTaskState(cur.copy(currentStep = text))`.
- `/task expect <text>` — `setTaskState(cur.copy(expectedAction = text))`.
- `/task plan <text>` — `setTaskState(cur.copy(approvedPlan = text))`.
- `/task back` — `agent.revertTaskState()`; warn если null (history пуста).
- `/task done` — если `stage == VALIDATION` → `advanceTaskState()`; иначе `forceSet(DONE)`.
- `/task reset` — `setTaskState(null)` (FSM сброшен; `currentTask`/`plan` в WorkingMemory остаются).
- `else` → `Unknown /task command: ...`.

Все ветки, требующие активной задачи, проверяют `getTaskState() == null` → подсказка `start`.
`start`/`next`/`back` не кидают (исключения гасятся в `TaskStateMachine`/accessors возвращом null),
но `set` оборачивает `valueOf` в try/catch.

### 4. `printHelp()` — секция `/task` (после `/profile clear`)
```
|  /task                 — Show task state (stage/step/expected action)
|  /task start <text>    — Start a task (stage: planning)
|  /task next            — Advance to next stage (clarify→planning→execution→validation→done)
|  /task set <stage>     — Force-set stage (clarify, planning, execution, validation, done)
|  /task step <text>     — Set current step
|  /task expect <text>   — Set expected action
|  /task plan <text>     — Set approved plan
|  /task back            — Revert one stage (by history)
|  /task done            — Mark task done (advance from validation or force)
|  /task reset           — Clear task state (FSM only; working memory kept)
```

### 5. Версия
Стартовая строка и заголовок help: `CLI Agent v0.5` → `v0.6`.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `/task start impl FSM` → `/task show` показывает `Stage: planning`.
- `/task next` → `planning → execution`.
- `/task set execution` из `planning` → warn о незаконном переходе, но стадия `execution`.
- `/task reset` → `/task show` = `No active task`.

## Зависимости
Задача 06.
