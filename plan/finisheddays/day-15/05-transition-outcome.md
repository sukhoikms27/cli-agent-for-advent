# Задача 05. `TransitionOutcome` — sealed результат перехода (Этап A)

## Цель
Типобезопасный результат попытки перехода вместо исключения. Нарушаемая сейчас конвенция проекта
(«sealed class Result, без исключений для flow control») — `TaskStateMachine.transition` кидает
`IllegalArgumentException`, потребитель не получает причину блокировки машиночитаемо. Вводим sealed
`TransitionOutcome` с тремя исходами.

## Зависимости
01 (`TaskStage` — есть с дня 13), 02 (`TaskState` — есть). Новый файл не зависит от новых задач.

## Файл (новый)
`src/main/kotlin/com/cliagent/state/TransitionOutcome.kt`

## Что реализовать

```kotlin
package com.cliagent.state

import kotlinx.serialization.Serializable

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
 * данные для реакции. `@Serializable` добавлен условно для возможности логирования/передачи —
 * если в проекте нет нужды, аннотацию можно убрать (guard не персистит outcome).
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
     * [hint] — человекочитаемая подсказка, какой артефакт нужен (из `gateHint`).
     */
    data class ArtifactMissing(
        val from: TaskStage,
        val to: TaskStage,
        val hint: String
    ) : TransitionOutcome()
}
```

## Ключевые инварианты
- **Sealed, не exception** — потребитель делает `when (outcome)` с exhaustiveness-check компилятора;
  нельзя забыть обработать ветку. Соответствует конвенции проекта (`InvariantResult` из дня 14 — тот
  же паттерн `sealed class` с `Valid`/`Violated`).
- **Три исхода, не два** — важно разделить `Illegal` (перепрыгивание, «вообще нельзя») и
  `ArtifactMissing` (можно, но не сейчас — нужен артефакт). Это разные реакции пользователя: в первом
  случае — `/task set --force` или выбор легальной цели, во втором — заполнить артефакт (`/task plan`
  и т.д.).
- **`Allowed` несёт `newState` целиком** — потребитель не вычисляет переход повторно; guard уже
  применил `transition`/`forceSet` и вернул результат. Это устраняет двойную работу и рассинхрон.
- **`Illegal.allowedTargets`** — для человекочитаемого сообщения «можно перейти только в: …».
- **`ArtifactMissing.hint`** — переиспользует `gateHint(stage)` (уже есть в `ChatCommand`), но
  вычисляется в guard, не в CLI.

## Решения
- **Не персистится в TaskState** — это результат операции, не состояние. `newState` внутри
  `Allowed` — уже персистируемое состояние (consumer кладёт его через `setTaskState`).
- **`@Serializable` опционален** — оставлен для единообразия с `InvariantResult`, но если добавляет
  лишнюю зависимость в чисто-transient тип, можно убрать. Решение на этапе реализации: проверить,
  есть ли необходимость логировать/передавать outcome.
- **Не используется в `TaskStateMachine` напрямую** — guard (задача 07) — потребитель FSM; сам FSM
  (`isAllowed`/`transition`/`canAdvance`) остаётся чистой логикой и НЕ меняется по сигнатуре (его 17
  тестов остаются зелёными). Guard — композиция над FSM.

## Критерии готовности
- Файл компилируется, sealed class с тремя data-class ветками.
- Поля: `Allowed(newState)`, `Illegal(from,to,allowedTargets)`, `ArtifactMissing(from,to,hint)`.
- Импорт `TaskStage`/`TaskState` разрешён (тот же пакет `state`).

## Зависимости (задачи)
Используется в 07 (`TransitionGuard`), 09 (`attemptTransition`), 10 (`/task set`).
