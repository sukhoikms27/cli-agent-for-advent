# Задача 18. `InteractionMode` + поле `WorkingMemory.interactionMode` (п.3)

## Цель
Enum режимов автоматизации (`MANUAL`/`PLAN`/`AUTO`) + поле в `WorkingMemory` (per-chat, персистится).
Default `PLAN` = текущее поведение → нулевая регрессия.

## Зависимости
`WorkingMemory` (день 11/13). Schema-evolution паттерн (`taskState`/`awaitingAdvance`).

## Файлы

### 1. Новый: `src/main/kotlin/com/cliagent/state/InteractionMode.kt`

```kotlin
package com.cliagent.state

import kotlinx.serialization.Serializable

/**
 * Режим взаимодействия для стадийного потока задачи (день 15, доп. п.3).
 *
 * Управляет степенью автоматизации жизненного цикла (clarify→plan→execute→validate→done) — как
 * hot-key режимы в Claude Code. Хранится в [com.cliagent.memory.WorkingMemory.interactionMode]
 * (per-chat, персистится, default [PLAN]).
 *
 * - [MANUAL] — свободный текст = обычный чат; FSM только через явный `/task start`/`next`/`set`.
 *   Авто-роутинг интента (задача 03) отключён. Полный контроль пользователя.
 * - [PLAN]   — текущее поведение (день 13): stage-поток с подтверждением каждого перехода
 *   (`awaitingAdvance`; «да» = переход). Авто-роутинг активен (QUESTION→чат, TASK→автостарт).
 * - [AUTO]   — полная автоматизация: переходы без подтверждения (авто-advance после готовности
 *   артефакта). Авто-роутинг активен. Пользователь наблюдает прогресс через StageAnnouncer (п.2).
 *
 * Во ВСЕХ режимах `TransitionGuard` соблюдается: перепрыгивание этапа блокируется (AUTO не даёт
 * обойти контролируемые переходы — только автоматизирует подтверждение).
 */
@Serializable
enum class InteractionMode { MANUAL, PLAN, AUTO }
```

### 2. Правка: `src/main/kotlin/com/cliagent/memory/MemoryLayer.kt` — `WorkingMemory`

**Сейчас** (строки 23–34):
```kotlin
@Serializable
data class WorkingMemory(
    val currentTask: String? = null,
    val plan: String? = null,
    val scratchNotes: String? = null,
    val taskDecisions: List<String> = emptyList(),
    val taskState: TaskState? = null
) {
    fun isEmpty(): Boolean =
        currentTask == null && plan == null && scratchNotes == null && taskDecisions.isEmpty() &&
            taskState == null
}
```

**После:** добавить поле `interactionMode` (default `PLAN`):
```kotlin
@Serializable
data class WorkingMemory(
    val currentTask: String? = null,
    val plan: String? = null,
    val scratchNotes: String? = null,
    val taskDecisions: List<String> = emptyList(),
    val taskState: TaskState? = null,   // день 13
    val interactionMode: InteractionMode = InteractionMode.PLAN   // день 15, п.3 (default = текущее поведение)
) {
    fun isEmpty(): Boolean =
        currentTask == null && plan == null && scratchNotes == null && taskDecisions.isEmpty() &&
            taskState == null && interactionMode == InteractionMode.PLAN
}
```

## Логика
- **Default `PLAN`** — текущее поведение day-13 (подтверждение переходов). Старые чаты без поля
  грузятся с default `PLAN` (schema-evolution через AppJson `ignoreUnknownKeys`/`explicitNulls=false`
  + enum coerce) → нулевая регрессия.
- **`isEmpty()` учитывает режим** — `WorkingMemory()` (всё default) = empty; `interactionMode = AUTO`
  → не empty (режим — значимое состояние). Это консистентно с `taskState != null → не empty`.
- **Per-chat** — режим привязан к задаче/чату, как `taskState`. Разные задачи — разные режимы.
  Не global (пользователь может хотеть AUTO для рутины, PLAN для сложного).
- **`@Serializable`** — персистится в JSON чата; `coerceInputValues` для enum (как `TaskStage`).

## Ключевые инварианты
- **Default `PLAN`** — обратная совместимость; существующие тесты `WorkingMemory` (`isEmpty()` = true
  для дефолта) остаются зелёными.
- **`AUTO` уважает `TransitionGuard`** — автоматизация подтверждения, не обход проверок. Перепрыгивание
  блокируется во всех режимах (это ядро задания курса дня 15).
- **`MANUAL` отключает авто-роутинг** (задача 03/20) — пользователь хочет полный контроль; FSM только
  через явные `/task`-команды.
- **Не в `TaskState`** — режим — это настройка взаимодействия, не состояние задачи. Логически отдельная
  ось (задача может быть в любой стадии при любом режиме).

## Решения
- **Три режима, не два** — `MANUAL`/`PLAN`/`AUTO` покрывают спектр «полный контроль ↔ полная
  автоматизация» с разумной серединой (`PLAN` = текущее). Согласовано с пользователем.
- **Per-chat, не global** — гибкость; пользователь настраивает режим под задачу. Global-режим
  (env-var/CLI-flag) можно добавить как override, но default — per-chat.
- **Default `PLAN`, не `AUTO`** — безопасность: новая фича (AUTO) не меняет поведение по умолчанию;
  пользователь явно выбирает автоматизацию.
- **`isEmpty()` проверяет режим** — иначе `WorkingMemory(interactionMode = AUTO)` считался бы empty и
  не рендерился бы в промпт; но режим — значимая настройка,值得 отображения.

## Тесты
Расширить `ChatDataSchemaEvolutionTest` (или `WorkingMemory`-тест, если есть): legacy JSON без
`interactionMode` грузится с default `PLAN`. Прямой тест `WorkingMemory().interactionMode == PLAN`.

## Критерии готовности
- `InteractionMode` enum с `MANUAL`/`PLAN`/`AUTO`.
- `WorkingMemory.interactionMode` с default `PLAN`.
- `isEmpty()` учитывает режим.
- Старый JSON (без поля) грузится с `PLAN` (schema-evolution тест зелёный).
- `WorkingMemory()` = empty (существующий тест зелёный).

## Зависимости (задачи)
Используется в 03 (роутинг MANUAL-off), 19 (`/mode`), 20 (orchestrator AUTO). Бамп-строка режима в
заголовке (задача 15) подключается здесь.
