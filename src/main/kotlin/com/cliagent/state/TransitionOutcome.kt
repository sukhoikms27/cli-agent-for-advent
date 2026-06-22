package com.cliagent.state

/**
 * Результат попытки перехода между стадиями через [TransitionGuard] (день 15).
 *
 * Заменяет `IllegalArgumentException` из [TaskStateMachine.transition] на типобезопасный sealed
 * результат — потребитель (CLI/оркестратор/агент) получает причину блокировки машиночитаемо и сам
 * решает, как отреагировать (сообщение пользователю, retry, force).
 *
 * Три исхода:
 * - [Allowed]          — переход выполнен (легальный + артефакт готов, либо `--force`).
 * - [Illegal]          — переход структурно запрещён (пары `from→to` нет в `ALLOWED`).
 * - [ArtifactMissing]  — переход легальный и канонический вперёд, но артефакт текущей стадии не готов.
 *
 * НЕ сериализуется в [TaskState] (это transient-результат операции, не состояние); несёт только
 * данные для реакции.
 */
sealed class TransitionOutcome {

    /** Переход выполнен. [newState] — обновлённое состояние (stage = to, дописана StageTransition). */
    data class Allowed(val newState: TaskState) : TransitionOutcome()

    /**
     * Переход структурно запрещён: пары `from→to` нет в `ALLOWED` (перепрыгивание через стадию).
     * [allowedTargets] — куда из [from] можно перейти легально (для подсказки пользователю).
     */
    data class Illegal(
        val from: TaskStage,
        val to: TaskStage,
        val allowedTargets: Set<TaskStage>
    ) : TransitionOutcome()

    /**
     * Переход легальный и канонический вперёд, но артефакт текущей стадии не готов (artifact-gate).
     * [hint] — человекочитаемая подсказка, какой артефакт нужен.
     */
    data class ArtifactMissing(
        val from: TaskStage,
        val to: TaskStage,
        val hint: String
    ) : TransitionOutcome()
}
