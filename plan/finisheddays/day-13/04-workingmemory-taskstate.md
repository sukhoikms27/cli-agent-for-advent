# Задача 04. Слот taskState в WorkingMemory

## Цель
Разместить состояние задачи в рабочей памяти (per-chat): персистентность и очистка на `/reset`
достаются бесплатно от Day 11.

## Файл (правка)
`src/main/kotlin/com/cliagent/memory/MemoryLayer.kt`

## Что изменить

### 1. Импорт
```kotlin
package com.cliagent.memory

import com.cliagent.state.TaskState
import kotlinx.serialization.Serializable
```

### 2. Поле + isEmpty() в `WorkingMemory` (вместо placeholder-комментария)
```kotlin
/**
 * Рабочая память — per-chat, данные текущей задачи.
 * Хранится в [ChatData.workingMemory], сбрасывается при /reset.
 *
 * [taskState] — состояние задачи как конечный автомат (день 13): этап/шаг/ожидаемое действие.
 * Живёт здесь (per-chat), персистится и очищается вместе с рабочей памятью.
 */
@Serializable
data class WorkingMemory(
    val currentTask: String? = null,
    val plan: String? = null,
    val scratchNotes: String? = null,
    val taskDecisions: List<String> = emptyList(),
    val taskState: TaskState? = null   // день 13: состояние задачи (FSM)
) {
    fun isEmpty(): Boolean =
        currentTask == null && plan == null && scratchNotes == null && taskDecisions.isEmpty() &&
            taskState == null
}
```

## Совместимость
- Старые чаты без `taskState` грузятся как `null` (`ignoreUnknownKeys=true` +
  `explicitNulls=false` в AppJson) — без миграций.
- `JsonChatStore.saveWorkingMemory` сериализует весь `WorkingMemory` через
  `ChatData.serializer()` → никаких новых методов `MemoryStore` не нужно.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `WorkingMemory().isEmpty()` == true (taskState=null).
- Существующие тесты PromptBuilder «empty WorkingMemory produces base content» зелёные.

## Зависимости
Задача 02.
