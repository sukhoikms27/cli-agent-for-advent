package com.cliagent.state

/**
 * Единый арбитр переходов между стадиями задачи (день 15).
 *
 * Объединяет две проверки [TaskStateMachine] в одну операцию с типобезопасным результатом
 * [TransitionOutcome]:
 *  - структурную ([TaskStateMachine.isAllowed] — пара `from→to` в `ALLOWED`),
 *  - артефактную ([TaskStateMachine.canAdvance] — артефакт текущей стадии готов).
 *
 * Все переходы в системе (CLI `/task`, оркестратор, агент) проводятся через [attempt] — единый
 * путь. Это устраняет разрозненные проверки (`canAdvance` в нескольких местах CLI) и даёт
 * машиночитаемую причину блокировки вместо исключения.
 *
 * Контракт (по решению пользователя «жёсткий режим + --force»):
 *  - нелегальный переход → [TransitionOutcome.Illegal] (блок; escape только через `force=true`);
 *  - легальный forward-canonical без артефакта → [TransitionOutcome.ArtifactMissing] (блок);
 *  - легальный (с артефактом ИЛИ боковой rework/replan ИЛИ `force`) → [TransitionOutcome.Allowed].
 *
 * Боковые переходы (validation→execution, execution→planning, done→planning) НЕ проходят
 * artifact-gate — это rework/replan/new-task, артефакт не требуется.
 */
object TransitionGuard {

    /**
     * Попытка перехода из текущей стадии в [to].
     *
     * @param state текущее состояние (источник стадии и артефактов)
     * @param to    целевая стадия
     * @param force если true — нелегальный переход выполняется через [TaskStateMachine.forceSet]
     *              (escape hatch `/task set --force`; note="forced" в history). Self-переход и
     *              artifact-gate при force игнорируются (осознанное действие).
     * @return [TransitionOutcome.Allowed] / [.Illegal] / [.ArtifactMissing]
     */
    fun attempt(state: TaskState, to: TaskStage, force: Boolean = false): TransitionOutcome {
        // 1. Force — осознанный override любых правил. Self-transition через forceSet — no-op
        //    (forceSet сам возвращает состояние без изменения history при from==to).
        if (force) {
            return TransitionOutcome.Allowed(TaskStateMachine.forceSet(state, to))
        }

        // 2. Self-transition — легальны всегда, no-op (transition возвращает состояние без history).
        if (state.stage == to) {
            return TransitionOutcome.Allowed(state)
        }

        // 3. Структурная проверка: пара from→to в ALLOWED?
        if (!TaskStateMachine.isAllowed(state.stage, to)) {
            return TransitionOutcome.Illegal(
                from = state.stage,
                to = to,
                allowedTargets = TaskStateMachine.allowedTargets(state.stage)
            )
        }

        // 4. Артефактная проверка: только для forward-canonical (to == next(from)).
        //    Боковые переходы (rework/replan/new-task) — без gate: они легальны без артефакта.
        val isForwardCanonical = TaskStateMachine.next(state.stage) == to
        if (isForwardCanonical && !TaskStateMachine.canAdvance(state)) {
            return TransitionOutcome.ArtifactMissing(
                from = state.stage,
                to = to,
                hint = gateHint(state.stage)
            )
        }

        // 5. Легальный переход (с артефактом или боковой) — выполняем.
        return TransitionOutcome.Allowed(TaskStateMachine.transition(state, to))
    }

    /** Человекочитаемая подсказка, какой артефакт нужен для forward-перехода со стадии. */
    private fun gateHint(stage: TaskStage): String = when (stage) {
        TaskStage.PLANNING -> "Set approved plan: /task plan <text>"
        TaskStage.EXECUTION -> "Set implementation: /task impl <text>"
        TaskStage.VALIDATION -> "Set verdict: /task verdict <text>"
        else -> "Artifact not ready."
    }
}
