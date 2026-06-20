# Задача 10. `/task set --force` + единая точка перехода в `ChatCommand` (Этап A)

## Цель
Жёсткий режим `/task set` (блок без `--force`) и единая точка перехода для `/task next`/`done` через
`attemptTransition`. Закрывает лазейку `forceSet`, перепрыгивавшую любой этап.

## Зависимости
09 (`attemptTransition`), 07 (`TransitionGuard`). Существующий `handleTask` (день 13).

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — `handleTask` (строки 761–952), особенно ветки
`set` (825–846), `next` (806–824), `done` (929–947).

## Что изменить

### Ветка `set` (строки 825–846) — жёсткий режим + `--force`

**Сейчас:** проверяет `isAllowed`, печатает warn, но **всё равно форсирует** через `forceSet`
(лазейка).

**После:** парсит `--force` из аргументов; без `--force` при `Illegal`/`ArtifactMissing` — блок с
подсказкой; с `--force` — осознанный escape.

```kotlin
"set" -> {
    if (parts.size < 3) {
        AppTerminal.println("Usage: /task set <clarify|planning|execution|validation|done> [--force]")
        return
    }
    val cur = agent.getTaskState()
    if (cur == null) {
        AppTerminal.println("No active task. Use: /task start <description>")
        return
    }
    val stageArg = parts[2]
    val stage = try {
        TaskStage.valueOf(stageArg.uppercase())
    } catch (e: IllegalArgumentException) {
        AppTerminal.println("Unknown stage: $stageArg. Use: clarify, planning, execution, validation, done")
        return
    }
    val force = parts.any { it.equals("--force", ignoreCase = true) || it == "-f" }
    when (val outcome = agent.attemptTransition(stage, force)) {
        is TransitionOutcome.Allowed ->
            AppTerminal.ok("Stage set to: ${outcome.newState.stage.name.lowercase()}" +
                if (force) " (forced)" else "")
        is TransitionOutcome.Illegal -> {
            val targets = outcome.allowedTargets.joinToString(", ") { it.name.lowercase() }
            AppTerminal.warn("⛔ Blocked: illegal transition ${outcome.from.name.lowercase()}" +
                "→${outcome.to.name.lowercase()}. Allowed: $targets. Use --force to override.")
        }
        is TransitionOutcome.ArtifactMissing -> {
            AppTerminal.warn("⛔ Blocked: stage ${outcome.from.name.lowercase()} not ready " +
                "(missing artifact). ${outcome.hint} Use --force to override.")
        }
        null -> AppTerminal.println("No active task. Use: /task start <description>")
    }
}
```

### Ветка `next` (строки 806–824) — через `attemptTransition`

**Сейчас:** вручную проверяет `canAdvance` + `advanceTaskState`.

**После:** единый путь через `attemptTransition(next)` — guard сам делает и структурную, и
артефактную проверку:

```kotlin
"next" -> {
    val cur = agent.getTaskState()
    if (cur == null) {
        AppTerminal.println("No active task. Use: /task start <description>")
        return
    }
    val nextStage = TaskStateMachine.next(cur.stage)
    if (nextStage == null) {
        AppTerminal.warn("No next stage from ${cur.stage.name.lowercase()} (already done?).")
        return
    }
    when (val outcome = agent.attemptTransition(nextStage)) {
        is TransitionOutcome.Allowed ->
            AppTerminal.ok("Advanced to stage: ${outcome.newState.stage.name.lowercase()}")
        is TransitionOutcome.ArtifactMissing ->
            AppTerminal.warn("Stage ${cur.stage.name.lowercase()} not ready: missing artifact. " +
                "${outcome.hint}")
        is TransitionOutcome.Illegal ->
            // невозможно: next() всегда даёт легальный forward; защита от будущих изменений
            AppTerminal.warn("⛔ Blocked: illegal transition ${cur.stage.name.lowercase()}→" +
                "${nextStage.name.lowercase()}.")
        null -> AppTerminal.println("No active task. Use: /task start <description>")
    }
}
```

### Ветка `done` (строки 929–947) — через `attemptTransition`

**Сейчас:** из VALIDATION проверяет gate + advance; иначе `forceSet(DONE)` (лазейка).

**После:** если стадия VALIDATION → `attemptTransition(DONE)` (guard уважает artifact-gate);
если другая стадия → `attemptTransition(DONE)` даст `Illegal` (нельзя финал без валидации), и пользователь
должен пройти через VALIDATION или использовать `/task set done --force`:

```kotlin
"done" -> {
    val cur = agent.getTaskState()
    if (cur == null) {
        AppTerminal.println("No active task. Use: /task start <description>")
        return
    }
    when (val outcome = agent.attemptTransition(TaskStage.DONE)) {
        is TransitionOutcome.Allowed ->
            AppTerminal.ok("Task done. (stage: ${outcome.newState.stage.name.lowercase()})")
        is TransitionOutcome.ArtifactMissing ->
            AppTerminal.warn("⛔ Blocked: validation not ready (missing verdict). ${outcome.hint} " +
                "Use /task set done --force to override.")
        is TransitionOutcome.Illegal -> {
            val targets = outcome.allowedTargets.joinToString(", ") { it.name.lowercase() }
            AppTerminal.warn("⛔ Blocked: cannot finish from ${cur.stage.name.lowercase()}. " +
                "Reach VALIDATION first. Allowed from here: $targets. Use /task set done --force to override.")
        }
        null -> AppTerminal.println("No active task. Use: /task start <description>")
    }
}
```

### Импорты в `ChatCommand.kt`
```kotlin
import com.cliagent.state.TransitionOutcome
```
(`TransitionGuard` не нужен в CLI — вызывается через `agent.attemptTransition`.)

### Удаление `gateHint` (строки 956–962)
Больше не используется в CLI — подсказки теперь приходят из `TransitionOutcome.ArtifactMissing.hint`
(guard). Удалить приватный метод `gateHint` из `ChatCommand` (он продублирован в guard как источник
правды). Проверить отсутствие других вызовов перед удалением.

## Поведенческий контраст (до/после)

| Команда | До (день 13) | После (день 15) |
|---|---|---|
| `/task set execution` (из planning, без plan) | ⚠️ warn + **force** (перепрыг) | ⛔ Blocked: ArtifactMissing, подсказка `/task plan` |
| `/task set done` (из planning) | ⚠️ warn + **force** (перепрыг) | ⛔ Blocked: Illegal, «Allowed: planning, execution» |
| `/task set done --force` | (не было `--force`) | ⚠️ Stage set to: done (forced) — осознанный escape |
| `/task next` (из planning, без plan) | ⛔ warn (gate) | ⛔ Blocked: ArtifactMissing (то же поведение, единый путь) |
| `/task done` (из execution, не validation) | ⚠️ force to done (лазейка) | ⛔ Blocked: Illegal, «достигни VALIDATION» |

## Ключевые инварианты
- **Жёсткий режим по умолчанию** — `/task set` без `--force` блокирует нелегальные/неподготовленные
  переходы. Это intentional (решение пользователя); `--force` сохраняет escape-hatch.
- **Единая точка** — `/task set`/`next`/`done` все через `agent.attemptTransition`; guard —
  единственный арбитр. `canAdvance`/`isAllowed`/`forceSet` больше не вызываются напрямую в CLI.
- **Сообщения информативные** — `Illegal` показывает `allowedTargets`; `ArtifactMissing` показывает
  `hint` с конкретной командой. Пользователь понимает, ЧТО делать.
- **`/task next` из Illegal невозможен** — `next()` всегда даёт легальный forward-canonical; ветка
  Illegal оставлена как защита от будущих изменений FSM (defensive).
- **`/task done` больше не форсирует из не-validation** — закрывает лазейку «финал без валидации».
  Пользователь должен пройти VALIDATION (нормальный путь) или явно `--force`.

## Решения
- **`-f` как alias `--force`** — по конвенции CLI; короткий флаг удобнее.
- **Парсинг `--force` через `parts.any`** — простой и устойчивый к позиции аргумента
  (`/task set execution --force` и `/task set --force execution` оба работают).
- **Удаление дублирующего `gateHint`** — устраняет рассинхрон подсказок между CLI и guard. Guard —
  источник правды.

## Критерии готовности
- `/task set execution` без plan → `⛔ Blocked: … ArtifactMissing`, стадия НЕ меняется.
- `/task set done` из planning → `⛔ Blocked: … Illegal`, стадия НЕ меняется.
- `/task set execution --force` → `Stage set to: execution (forced)`, note="forced" в history.
- `/task next` без plan → `⛔ Blocked: ArtifactMissing` (поведение как раньше, но через guard).
- `/task done` из execution → `⛔ Blocked: Illegal` (больше не форсирует).
- `/task done` из validation без verdict → `⛔ Blocked: ArtifactMissing`.
- `/task done` из validation с verdict → `Task done`.
- `gateHint` удалён из `ChatCommand`.

## Зависимости (задачи)
09. Completer в 11. Демо-кейсы B/C в 25.
