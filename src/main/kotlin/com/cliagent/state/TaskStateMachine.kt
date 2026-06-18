package com.cliagent.state

import java.time.Instant

/**
 * Конечный автомат состояния задачи (день 13) — чистая логика переходов.
 *
 * Не зависит от LLM/агента/персистентности: чистые функции над [TaskState].
 * Продвижение стадии — только ручное через `/task` (см.
 * [com.cliagent.cli.ChatCommand.handleTask]).
 *
 * Две модели перехода:
 *  - [transition] — строгий: кидает [IllegalArgumentException] на запрещённый переход
 *    (используется `/task next`);
 *  - [forceSet] — принудительный: игнорирует правила, помечает запись `note="forced"`
 *    (используется `/task set <stage>` — override с предупреждением пользователю).
 *
 * Self-transitions (`from == to`) НЕ добавляются в [TaskState.stageHistory], чтобы
 * [back] корректно откатывал реальные переходы, а не no-op после `/task set` на ту же стадию.
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

    /** Разрешён ли переход `from → to` (включая self-transitions). */
    fun isAllowed(from: TaskStage, to: TaskStage): Boolean =
        from == to || ALLOWED.contains(from to to)

    /** Канонический следующий этап вперёд. `DONE` → null (некуда). */
    fun next(from: TaskStage): TaskStage? = when (from) {
        TaskStage.CLARIFY -> TaskStage.PLANNING
        TaskStage.PLANNING -> TaskStage.EXECUTION
        TaskStage.EXECUTION -> TaskStage.VALIDATION
        TaskStage.VALIDATION -> TaskStage.DONE
        TaskStage.DONE -> null
    }

    /**
     * Artifact-gate (доработка Day 13, Вариант 2 автора курса): разрешён ли канонический
     * переход вперёд — артефакт текущей стадии готов («родился артефакт — поехали дальше»).
     * `CLARIFY` — опц. стадия, без обязательного артефакта; `DONE` — некуда.
     * Принудительный `/task set` (см. [forceSet]) минует этот gate.
     */
    fun canAdvance(state: TaskState): Boolean = when (state.stage) {
        TaskStage.CLARIFY -> true
        TaskStage.PLANNING -> !state.approvedPlan.isNullOrBlank()
        TaskStage.EXECUTION -> !state.implementation.isNullOrBlank()
        TaskStage.VALIDATION -> !state.verdict.isNullOrBlank()
        TaskStage.DONE -> false
    }

    /**
     * Строгий переход: если `!isAllowed` — кидает [IllegalArgumentException],
     * иначе возвращает обновлённое состояние (stage = [to], дописана [StageTransition]).
     * Self-transition возвращает состояние без изменения history.
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

    /**
     * Принудительная установка стадии (override): игнорирует правила перехода,
     * всегда дописывает [StageTransition] с `note` (default "forced"). Self-transition — no-op.
     */
    fun forceSet(state: TaskState, to: TaskStage, note: String? = "forced"): TaskState {
        if (state.stage == to) return state
        return state.copy(
            stage = to,
            stageHistory = state.stageHistory + StageTransition(state.stage, to, nowIso(), note)
        )
    }

    /**
     * Откат на одну стадию назад по [TaskState.stageHistory]: восстанавливает `from`
     * последней записи и удаляет её. `null` если история пуста.
     */
    fun back(state: TaskState): TaskState? {
        val last = state.stageHistory.lastOrNull() ?: return null
        return state.copy(
            stage = last.from,
            stageHistory = state.stageHistory.dropLast(1)
        )
    }

    private fun nowIso(): String = Instant.now().toString()
}
