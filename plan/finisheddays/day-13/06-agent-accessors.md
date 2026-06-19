# Задача 06. Аксессоры task state в ContextAwareAgent

## Цель
Агент умеет читать/писать состояние задачи и выполнять канонический переход вперёд/назад с
персистентностью. Хук в `chat()` НЕ нужен (продвижение только ручное).

## Файл (правка)
`src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt`

## Что изменить

### 1. Импорты
```kotlin
import com.cliagent.memory.UserProfile
import com.cliagent.memory.WorkingMemory
import com.cliagent.state.TaskState
import com.cliagent.state.TaskStateMachine
```

### 2. Аксессоры (после `getProfile/setProfile`, по тому же образцу)
```kotlin
// ── Task state accessors (день 13, для /task команды) ──

suspend fun getTaskState(): TaskState? = getWorkingMemory()?.taskState

suspend fun setTaskState(state: TaskState?) {
    val w = getWorkingMemory() ?: WorkingMemory()
    setWorkingMemory(w.copy(taskState = state))
}

/** Канонический переход вперёд (TaskStateMachine.next); null если некуда/нет задачи. */
suspend fun advanceTaskState(note: String? = null): TaskState? {
    val cur = getTaskState() ?: return null
    val nextStage = TaskStateMachine.next(cur.stage) ?: return null
    val updated = TaskStateMachine.transition(cur, nextStage, note)
    setTaskState(updated)
    return updated
}

/** Откат на одну стадию назад по history; null если история пуста/нет задачи. */
suspend fun revertTaskState(): TaskState? {
    val cur = getTaskState() ?: return null
    val reverted = TaskStateMachine.back(cur) ?: return null
    setTaskState(reverted)
    return reverted
}
```

## Инвариант совместимости
Без новых constructor-параметров поведение идентично Day 12: `chat()` не трогаем, доп.
LLM-вызовов нет. `reset()` уже чистит `workingMemory` (→ `taskState`) — без изменений.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `setTaskState(TaskState(stage=EXECUTION))` персистит и читается через `getTaskState()`.
- `setTaskState` НЕ затирает `currentTask`/`plan`/`taskDecisions` (как `setProfile` сохраняет
  knowledge/decisions).
- `reset()` → `getTaskState()` == null.

## Зависимости
Задачи 03, 04.
