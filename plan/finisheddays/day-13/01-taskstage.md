# Задача 01. TaskStage — enum этапов задачи

## Цель
Определить этапы задачи как `@Serializable` enum — основа конечного автомата.

## Файл (новый)
`src/main/kotlin/com/cliagent/state/TaskStage.kt`

## Что реализовать
```kotlin
package com.cliagent.state

import kotlinx.serialization.Serializable

/**
 * Этап задачи (день 13 — task state machine).
 *
 * Канонический поток: clarify → planning → execution → validation → done.
 *  clarify   — уточнение требований (опц. стартовая стадия)
 *  planning  — планирование
 *  execution — реализация
 *  validation — проверка
 *  done      — завершено
 *
 * Разрешённые переходы (см. TaskStateMachine):
 *  - clarify → planning
 *  - planning → execution
 *  - execution → validation
 *  - validation → done            (успешная проверка)
 *  - validation → execution       (доработка)
 *  - execution → planning         (перепланирование)
 *  - done → planning              (новая задача)
 *  - self-transitions разрешены (idempotent для /task set на ту же стадию)
 *
 * Порядок объявления важен для coerceInputValues AppJson (первый = CLARIFY):
 * неизвестное значение enum у старых чатов коэрцится к default.
 */
@Serializable
enum class TaskStage { CLARIFY, PLANNING, EXECUTION, VALIDATION, DONE }
```

## Конвенции
- Пакет `com.cliagent.state` (новый, lowercase, без подчёркиваний).
- `@Serializable` — для персистентности в `WorkingMemory.taskState`.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `TaskStage.entries` = `[CLARIFY, PLANNING, EXECUTION, VALIDATION, DONE]`.

## Зависимости
—
