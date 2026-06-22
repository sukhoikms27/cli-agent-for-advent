# Задача 09. `ContextAwareAgent.attemptTransition` (Этап A)

## Цель
Аксессор агента для контролируемого перехода через `TransitionGuard` — мост между guard (чистая
логика) и агентом (управление персистентностью). Все CLI/оркестратор переходы идут через этот метод.

## Зависимости
07 (`TransitionGuard`). Существующие `getTaskState`/`setTaskState`/`advanceTaskState` (день 13).

## Файл (правка)
`src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt` — блок task-state accessors (~строки 280–305).

## Что изменить

Добавить метод рядом с `advanceTaskState`/`revertTaskState`:

```kotlin
// ── Task state accessors (день 13, для /task команды) ──

suspend fun getTaskState(): TaskState? = getWorkingMemory()?.taskState

suspend fun setTaskState(state: TaskState?) {
    val w = getWorkingMemory() ?: WorkingMemory()
    setWorkingMemory(w.copy(taskState = state))
}

/**
 * Контролируемый переход через [com.cliagent.state.TransitionGuard] (день 15).
 *
 * Единая точка перехода для CLI/оркестратора: проверяет легальность + артефакт, возвращает
 * типобезопасный [com.cliagent.state.TransitionOutcome]. При [Allowed] — персистит новое состояние.
 * При [Illegal]/[ArtifactMissing] — состояние НЕ меняется, потребитель сам решает реакцию.
 *
 * @param to    целевая стадия
 * @param force осознанный escape (note="forced" в history); обходит все правила
 * @return outcome перехода; null если нет активной задачи (taskState == null)
 */
suspend fun attemptTransition(
    to: TaskStage,
    force: Boolean = false
): TransitionOutcome? {
    val cur = getTaskState() ?: return null
    val outcome = TransitionGuard.attempt(cur, to, force)
    if (outcome is TransitionOutcome.Allowed) {
        setTaskState(outcome.newState)
    }
    return outcome
}
```

## Обратная совместимость `advanceTaskState`

Существующий `advanceTaskState` (день 13) делегирует в FSM напрямую (`TaskStateMachine.transition`).
В день 15 он **перестраивается на guard** для единого пути — но сохраняет сигнатуру и семантику
(канонический forward, кидает исключение при нелегальном — как раньше, т.к. `next` всегда даёт
легальную цель, исключение не случится на практике):

```kotlin
/**
 * Канонический переход вперёд ([TaskStateMachine.next]); null если некуда/нет задачи.
 *
 * День 15: делегирует в [attemptTransition] (единый путь через guard). `next(stage)` всегда
 * возвращает легальный forward-canonical переход, поэтому outcome = Allowed или ArtifactMissing
 * (Illegal невозможен). При ArtifactMissing — поведение как раньше: состояние не меняется,
 * возвращается null (канонический advance «не состоялся»). Для машиночитаемой причины блокировки
 * используй [attemptTransition] напрямую.
 */
suspend fun advanceTaskState(note: String? = null): TaskState? {
    val cur = getTaskState() ?: return null
    val nextStage = TaskStateMachine.next(cur.stage) ?: return null
    val outcome = attemptTransition(nextStage)
    return when (outcome) {
        is TransitionOutcome.Allowed -> outcome.newState
        is TransitionOutcome.ArtifactMissing,
        is TransitionOutcome.Illegal,
        null -> null
    }
}
```

> Примечание по `note`: в день 13 `advanceTaskState(note)` дописывал note в StageTransition. Guard
> через `transition` не принимает note (переход «нормальный», не forced). Если note критичен для
> аудита канонических переходов, добавить опциональный параметр в guard — но в текущей реализации
> note использовался только в тестах и `/task next` без практического потребителя. Решение:
> отказаться от note в `advanceTaskState` (deprecated параметр оставить как no-op для совместимости
> вызовов, либо убрать, проверив call-sites). Проверить call-sites `advanceTaskState` на этапе
> реализации.

## Импорты
Добавить в `ContextAwareAgent.kt`:
```kotlin
import com.cliagent.state.TransitionGuard
import com.cliagent.state.TransitionOutcome
```

## Логика
- **Guard — единственный путь** — агент не вызывает `TaskStateMachine.transition` напрямую в новом
  коде; всё через `attemptTransition`. Это централизует проверку (нельзя случайно обойти guard).
- **`Allowed` → персист** — только при разрешённом переходе меняется состояние. `Illegal`/
  `ArtifactMissing` возвращаются без side-effect (состояние не тронуто).
- **`null` при отсутствии задачи** — как `advanceTaskState`/`revertTaskState`; потребитель понимает
  «нет активной задачи» без separate-исключения.
- **`advanceTaskState` обратно совместим** — сигнатура та же, семантика (null при блокировке)
  сохранена; существующие вызовы (`/task next`, orchestrator) продолжают работать. Меняется только
  внутренний путь (через guard вместо прямого FSM).

## Тесты (правка)
`src/test/kotlin/com/cliagent/agent/ContextAwareAgentTaskStateTest.kt` — добавить:

```kotlin
// ── attemptTransition (день 15) ──

@Test
fun `attemptTransition returns null when no active task`() = runTest {
    val agent = newAgent()
    assertNull(agent.attemptTransition(TaskStage.EXECUTION))
}

@Test
fun `attemptTransition allowed persists new state`() = runTest {
    val agent = newAgent()
    agent.setTaskState(TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan"))
    val outcome = agent.attemptTransition(TaskStage.EXECUTION)
    assertTrue(outcome is TransitionOutcome.Allowed)
    assertEquals(TaskStage.EXECUTION, agent.getTaskState()?.stage)
}

@Test
fun `attemptTransition illegal does not change state`() = runTest {
    val agent = newAgent()
    agent.setTaskState(TaskState(stage = TaskStage.PLANNING, approvedPlan = "plan"))
    val outcome = agent.attemptTransition(TaskStage.DONE)
    assertTrue(outcome is TransitionOutcome.Illegal)
    assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)  // не изменилось
}

@Test
fun `attemptTransition artifact missing does not change state`() = runTest {
    val agent = newAgent()
    agent.setTaskState(TaskState(stage = TaskStage.PLANNING))  // нет плана
    val outcome = agent.attemptTransition(TaskStage.EXECUTION)
    assertTrue(outcome is TransitionOutcome.ArtifactMissing)
    assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
}

@Test
fun `attemptTransition force bypasses gate and persists`() = runTest {
    val agent = newAgent()
    agent.setTaskState(TaskState(stage = TaskStage.PLANNING))
    val outcome = agent.attemptTransition(TaskStage.DONE, force = true)
    assertTrue(outcome is TransitionOutcome.Allowed)
    assertEquals(TaskStage.DONE, agent.getTaskState()?.stage)
}

@Test
fun `advanceTaskState returns null on artifact missing via guard`() = runTest {
    // обратная совместимость: advanceTaskState делегирует в attemptTransition
    val agent = newAgent()
    agent.setTaskState(TaskState(stage = TaskStage.PLANNING))  // нет плана
    assertNull(agent.advanceTaskState())  // ArtifactMissing → null
    assertEquals(TaskStage.PLANNING, agent.getTaskState()?.stage)
}
```

## Критерии готовности
- `attemptTransition(to, force): TransitionOutcome?` работает: Allowed→персист, Illegal/
  ArtifactMissing→без изменений, null при отсутствии задачи.
- `advanceTaskState` обратно совместим (делегирует в guard, null при блокировке).
- Существующие тесты `ContextAwareAgentTaskStateTest` (7 шт., день 13) остаются зелёными.
- 6 новых тестов зелёные.

## Зависимости (задачи)
07. Используется в 10 (`/task set`), 14/20 (оркестратор).
