# Задача 03. TaskStateMachine — чистая логика переходов

## Цель
Конечный автомат: разрешённые переходы, канонический `next`, строгий `transition`,
принудительный `forceSet`, откат `back`. Чистые функции, без LLM/агента/персистентности.

## Файл (новый)
`src/main/kotlin/com/cliagent/state/TaskStateMachine.kt`

## Что реализовать
```kotlin
package com.cliagent.state

import java.time.Instant

/**
 * Конечный автомат состояния задачи (день 13) — чистая логика переходов.
 *
 * Две модели перехода:
 *  - transition — строгий: кидает IllegalArgumentException на запрещённый переход
 *    (используется /task next);
 *  - forceSet — принудительный: игнорирует правила, помечает note="forced"
 *    (используется /task set <stage> — override с предупреждением).
 *
 * Self-transitions (from == to) НЕ добавляются в stageHistory, чтобы back
 * откатывал реальные переходы, а не no-op после /task set на ту же стадию.
 */
object TaskStateMachine {

    private val ALLOWED: Set<Pair<TaskStage, TaskStage>> = setOf(
        TaskStage.CLARIFY to TaskStage.PLANNING,
        TaskStage.PLANNING to TaskStage.EXECUTION,
        TaskStage.EXECUTION to TaskStage.VALIDATION,
        TaskStage.VALIDATION to TaskStage.DONE,
        TaskStage.VALIDATION to TaskStage.EXECUTION,   // доработка
        TaskStage.EXECUTION to TaskStage.PLANNING,     // перепланирование
        TaskStage.DONE to TaskStage.PLANNING           // новая задача
    )

    /** Разрешён ли переход from → to (включая self-transitions). */
    fun isAllowed(from: TaskStage, to: TaskStage): Boolean =
        from == to || ALLOWED.contains(from to to)

    /** Канонический следующий этап вперёд. DONE → null (некуда). */
    fun next(from: TaskStage): TaskStage? = when (from) {
        TaskStage.CLARIFY -> TaskStage.PLANNING
        TaskStage.PLANNING -> TaskStage.EXECUTION
        TaskStage.EXECUTION -> TaskStage.VALIDATION
        TaskStage.VALIDATION -> TaskStage.DONE
        TaskStage.DONE -> null
    }

    /**
     * Строгий переход: если !isAllowed — IllegalArgumentException,
     * иначе обновлённое состояние (stage = to, дописана StageTransition).
     * Self-transition — без изменения history.
     */
    fun transition(state: TaskState, to: TaskStage, note: String? = null): TaskState {
        require(isAllowed(state.stage, to)) {
            "Illegal transition ${state.stage.name.lowercase()}→${to.name.lowercase()}"
        }
        if (state.stage == to) return state
        return state.copy(
            stage = to,
            stageHistory = state.stageHistory + StageTransition(state.stage, to, nowIso(), note)
        )
    }

    /** Принудительная установка (override): игнорирует правила, дописывает StageTransition
     *  с note (default "forced"). Self-transition — no-op. */
    fun forceSet(state: TaskState, to: TaskStage, note: String? = "forced"): TaskState {
        if (state.stage == to) return state
        return state.copy(
            stage = to,
            stageHistory = state.stageHistory + StageTransition(state.stage, to, nowIso(), note)
        )
    }

    /** Откат на одну стадию назад по stageHistory: восстанавливает from последней записи.
     *  null если история пуста. */
    fun back(state: TaskState): TaskState? {
        val last = state.stageHistory.lastOrNull() ?: return null
        return state.copy(
            stage = last.from,
            stageHistory = state.stageHistory.dropLast(1)
        )
    }

    private fun nowIso(): String = Instant.now().toString()
}
```

## Ключевые инварианты
- `isAllowed(x, x)` всегда true (self-transition idempotent).
- `transition` и `forceSet` при `from == to` НЕ мутируют history.
- `transition` кидает на запрещённом переходе; `forceSet` — никогда.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `transition(TaskState(stage=PLANNING), DONE)` кидает `IllegalArgumentException`.
- `forceSet(TaskState(stage=PLANNING), DONE)` проходит, `note="forced"`.

## Зависимости
Задачи 01, 02.
