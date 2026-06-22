# Задача 07. `TransitionGuard` — единый арбитр переходов (Этап A)

## Цель
Единая точка контроля всех переходов состояний. Объединяет структурную (`isAllowed`) и артефактную
(`canAdvance`) проверки в один `object` с методом `attempt`, возвращающим типобезопасный
`TransitionOutcome`. Все потребители (`/task next`/`set`/`done`, оркестратор, агент) проводят
переходы через guard — единственный путь.

## Зависимости
05 (`TransitionOutcome`), 06 (`allowedTargets`). Использует существующие `TaskStateMachine.isAllowed`
/`transition`/`forceSet`/`canAdvance`/`next` (день 13, не меняются).

## Файл (новый)
`src/main/kotlin/com/cliagent/state/TransitionGuard.kt`

## Что реализовать

```kotlin
package com.cliagent.state

/**
 * Единый арбитр переходов между стадиями задачи (день 15).
 *
 * Объединяет две проверки [TaskStateMachine] в одну операцию с типобезопасным результатом
 * [TransitionOutcome]:
 *  - структурную ([TaskStateMachine.isAllowed] — пара `from→to` в `ALLOWED`),
 *  - артефактную ([TaskStateMachine.canAdvance] — артефакт текущей стадии готов).
 *
 * Все переходы в системе (CLI `/task`, оркестратор, агент) проводятся через [attempt] — единственный
 * путь. Это устраняет разрозненные проверки (`canAdvance` в трёх местах CLI) и даёт машиночитаемую
 * причину блокировки вместо исключения.
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
     *              (escape hatch `/task set --force`; note="forced" в history). Само-переход и
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
```

## Поток (детально)

```
attempt(state, to, force=false):
  force?           → Allowed(forceSet(state, to))           [escape hatch, note="forced"]
  state.stage==to? → Allowed(state)                          [self-transition, no-op]
  !isAllowed?      → Illegal(from, to, allowedTargets(from)) [перепрыгивание, ⛔]
  forward-canonical && !canAdvance?
                   → ArtifactMissing(from, to, hint)         [артефакт не готов, ⛔]
  иначе            → Allowed(transition(state, to))          [легальный переход]
```

**Примеры:**
- `attempt(PLANNING, DONE)` → `Illegal` (планирование→финал запрещён; allowedTargets={PLANNING,EXECUTION}).
- `attempt(PLANNING, EXECUTION)` при пустом `approvedPlan` → `ArtifactMissing` (нужен план).
- `attempt(PLANNING, EXECUTION)` с `approvedPlan="..."` → `Allowed(EXECUTION)`.
- `attempt(VALIDATION, EXECUTION)` (rework) без артефакта → `Allowed(EXECUTION)` (боковой, без gate).
- `attempt(PLANNING, DONE, force=true)` → `Allowed(DONE)` через `forceSet` (note="forced").
- `attempt(PLANNING, PLANNING)` → `Allowed(state)` без изменения history.

## Ключевые инварианты
- **Единая точка** — все переходы через `attempt`. `TaskStateMachine` остаётся чистой логикой
  (его методы `transition`/`canAdvance` публичны и обратно совместимы, но в день-15 consumers идут
  через guard для единообразия).
- **Force игнорирует ВСЕ правила** — включая self и gate. Это осознанный escape (note="forced"
  в history для аудита). Self через forceSet — no-op (forceSet уже так делает).
- **canAdvance ТОЛЬКО для forward-canonical** (`next(from) == to`). Иначе блокировали бы rework
  (validation→execution не должен требовать verdict, как и execution→planning не требует
  implementation для перепланирования). Это устраняет ложные блокировки нормальных циклов.
- **Self-transition — Allowed(state) без history** — консистентно с `TaskStateMachine.transition`
  (self не дописывает history, иначе `/task back` делал бы no-op после self).
- **`gateHint` продублирован** из `ChatCommand` — намеренно: guard не должен зависеть от CLI-слоя
  (нарушение слоистости). CLI может иметь свой рендеринг, но guard даёт готовую подсказку. После
  рефакторинга (если будет) — вынести `gateHint` в общее место; пока допустимо.

## Решения
- **`object`, не класс** — чистая логика над `TaskStateMachine`, без состояния. Тестируется
  изолированно (чистые данные).
- **`force: Boolean = false`** — default безопасный (жёсткий режим); escape явный.
- **Порядок проверок: force → self → illegal → artifact → allowed** — force первым (осознанный
  escape имеет приоритет), self вторым (быстрый path без history), затем содержательные блокировки.
- **`isForwardCanonical` через `next(from) == to`** — переиспользование существующей канонической
  функции FSM, не дублируем знание о forward-переходах.

## Критерии готовности
- `TransitionGuard.attempt` возвращает корректный `TransitionOutcome` для всех 5 веток логики.
- Force-mode выполняет переход через `forceSet` (note="forced").
- Self-transition не меняет history.
- canAdvance применяется только к forward-canonical.

## Зависимости (задачи)
05, 06. Тестируется в 08. Используется в 09 (`attemptTransition`), 10 (`/task set`), 14/20
(оркестратор).
