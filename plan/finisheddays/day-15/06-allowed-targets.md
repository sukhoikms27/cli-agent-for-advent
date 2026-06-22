# Задача 06. `TaskStateMachine.allowedTargets(from)` (Этап A)

## Цель
Метод, возвращающий множество легальных целей перехода из данной стадии. Нужен для человекочитаемого
сообщения в `TransitionOutcome.Illegal` («можно перейти только в: planning, execution»). Не вычислять
его в CLI/guard каждый раз — инкапсулировать в FSM (единый источник правды о переходах).

## Зависимости
`TaskStateMachine` (день 13). Не зависит от новых задач (чистое дополнение к FSM).

## Файл (правка)
`src/main/kotlin/com/cliagent/state/TaskStateMachine.kt`

## Что изменить

Добавить метод рядом с `isAllowed` (после строки с `isAllowed`, ~строка 36):

```kotlin
/**
 * Множество стадий, в которые можно легально перейти из [from] (день 15).
 *
 * Включает саму [from] (self-transition) и все стадии из [ALLOWED], где [from] — источник.
 * Используется в [com.cliagent.state.TransitionOutcome.Illegal] для человекочитаемой подсказки
 * «можно перейти только в: …». DONE → {PLANNING} (новая задача); иные — по ALLOWED.
 *
 * Чистая функция над [ALLOWED] + [TaskStage.entries]; не мутирует состояние.
 */
fun allowedTargets(from: TaskStage): Set<TaskStage> =
    TaskStage.entries.filterTo(mutableSetOf()) { isAllowed(from, it) }
```

## Логика
- `TaskStage.entries.filterTo(mutableSetOf()) { isAllowed(from, it) }` — проходит по всем стадиям,
  оставляет те, куда `isAllowed(from, it)` = true. `isAllowed` уже включает self-transitions
  (`from == to || ALLOWED.contains(...)`) → `from` всегда в результате.
- Для CLARIFY → {CLARIFY, PLANNING}; PLANNING → {PLANNING, EXECUTION}; EXECUTION → {EXECUTION,
  VALIDATION, PLANNING}; VALIDATION → {VALIDATION, DONE, EXECUTION}; DONE → {DONE, PLANNING}.
- Чистая функция над `ALLOWED` + `entries` — единый источник правды (не дублируем `ALLOWED` в методе).

## Тест (правка)
`src/test/kotlin/com/cliagent/state/TaskStateMachineTest.kt` — добавить тесты:

```kotlin
// ── allowedTargets (день 15) ──

@Test
fun `allowedTargets includes self`() {
    TaskStage.entries.forEach { stage ->
        assertTrue(TaskStateMachine.allowedTargets(stage).contains(stage),
            "allowedTargets($stage) should include self")
    }
}

@Test
fun `allowedTargets for canonical forward stages`() {
    assertEquals(setOf(TaskStage.CLARIFY, TaskStage.PLANNING),
        TaskStateMachine.allowedTargets(TaskStage.CLARIFY))
    assertEquals(setOf(TaskStage.PLANNING, TaskStage.EXECUTION),
        TaskStateMachine.allowedTargets(TaskStage.PLANNING))
    assertEquals(setOf(TaskStage.EXECUTION, TaskStage.VALIDATION, TaskStage.PLANNING),
        TaskStateMachine.allowedTargets(TaskStage.EXECUTION))
    assertEquals(setOf(TaskStage.VALIDATION, TaskStage.DONE, TaskStage.EXECUTION),
        TaskStateMachine.allowedTargets(TaskStage.VALIDATION))
    assertEquals(setOf(TaskStage.DONE, TaskStage.PLANNING),
        TaskStateMachine.allowedTargets(TaskStage.DONE))
}

@Test
fun `allowedTargets excludes forbidden jumps`() {
    // planning → done запрещён (нельзя перепрыгнуть валидацию)
    assertFalse(TaskStateMachine.allowedTargets(TaskStage.PLANNING).contains(TaskStage.DONE))
    // clarify → execution запрещён (нельзя перепрыгнуть planning)
    assertFalse(TaskStateMachine.allowedTargets(TaskStage.CLARIFY).contains(TaskStage.EXECUTION))
}
```

## Ключевые инварианты
- **Не дублирует `ALLOWED`** — вычисляется из существующего `isAllowed`. Добавление новых переходов в
  `ALLOWED` автоматически отразится в `allowedTargets` (нет рассинхрона).
- **Всегда содержит `from`** — self-transition легальна по контракту `isAllowed`.
- **Возвращает `Set<TaskStage>`** — для `Illegal.allowedTargets` (порядок не важен, дедупликация).

## Критерии готовности
- Метод `allowedTargets(from): Set<TaskStage>` компилируется.
- 3 новых теста зелёные (включая self, канонические forward, исключение перепрыгиваний).
- Существующие 17 тестов `TaskStateMachineTest` остаются зелёными (метод аддитивный).

## Зависимости (задачи)
Используется в 07 (`TransitionGuard.attempt` → `Illegal(from, to, allowedTargets(from))`).
