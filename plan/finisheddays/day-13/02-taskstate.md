# Задача 02. TaskState + StageTransition — модель состояния

## Цель
Три элемента задания курса (этап / текущий шаг / ожидаемое действие) + история переходов.

## Файл (новый)
`src/main/kotlin/com/cliagent/state/TaskState.kt`

## Что реализовать
```kotlin
package com.cliagent.state

import kotlinx.serialization.Serializable

/**
 * Запись о переходе между стадиями (день 13).
 * Хранится в TaskState.stageHistory — лента, по которой /task back откатывает стадию.
 */
@Serializable
data class StageTransition(
    val from: TaskStage,
    val to: TaskStage,
    val at: String,            // ISO-метка (Instant.now().toString())
    val note: String? = null   // напр. "forced" для принудительного /task set
)

/**
 * Состояние текущей задачи (день 13 — task state machine).
 *
 * Этап (stage), текущий шаг (currentStep), ожидаемое действие (expectedAction) —
 * три элемента задания. Доп.: утверждённый план (approvedPlan), история переходов (stageHistory).
 *
 * Живёт внутри WorkingMemory.taskState (per-chat, персистится, чистится при /reset).
 * «Нет активной задачи» = taskState == null в WorkingMemory (не отдельный флаг).
 * Default stage = PLANNING — канонический старт /task start.
 */
@Serializable
data class TaskState(
    val stage: TaskStage = TaskStage.PLANNING,
    val currentStep: String? = null,
    val expectedAction: String? = null,
    val approvedPlan: String? = null,
    val stageHistory: List<StageTransition> = emptyList()
)
```

## Конвенции
- Все поля с дефолтами — эволюция схемы: добавлять можно, не ломая старый JSON
  (`ignoreUnknownKeys=true`, `explicitNulls=false` в AppJson).
- `at: String`, не `Instant` — сериализация без custom serializer.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `TaskState()` → `stage == PLANNING`, `stageHistory` пуст.

## Зависимости
Задача 01.
