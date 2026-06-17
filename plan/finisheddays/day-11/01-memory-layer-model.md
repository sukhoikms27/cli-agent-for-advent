# Задача 01. Модель слоёв памяти

## Цель
Завести явную модель трёх слоёв памяти — ядро deliverable дня 11. Чистые `@Serializable` data-классы, без логики.

## Файл (новый)
`src/main/kotlin/com/cliagent/memory/MemoryLayer.kt`

## Что реализовать
```kotlin
package com.cliagent.memory

import kotlinx.serialization.Serializable

enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

@Serializable
data class WorkingMemory(
    val currentTask: String? = null,
    val plan: String? = null,
    val scratchNotes: String? = null,
    val taskDecisions: List<String> = emptyList()
    // Day 13: val taskState: TaskState? = null  (add with default)
) {
    fun isEmpty(): Boolean =
        currentTask == null && plan == null && scratchNotes == null && taskDecisions.isEmpty()
}

@Serializable
data class UserProfile(            // forward-declared stub под Day 12
    val style: String? = null,
    val format: String? = null,
    val constraints: List<String> = emptyList()
)

@Serializable
data class LongTermMemory(
    val knowledge: Map<String, String> = emptyMap(),
    val decisions: Map<String, String> = emptyMap(),
    val profile: UserProfile? = null   // Day 12 заполняет
) {
    fun isEmpty(): Boolean =
        knowledge.isEmpty() && decisions.isEmpty() && profile == null
}
```

## Конвенции (CLAUDE.md)
- `@Serializable` на всех data-классах.
- Все поля defaulted/nullable → schema-safe, грузится из `{}` (`ignoreUnknownKeys=true` + `encodeDefaults=true`).
- `UserProfile` — stub для Day 12; рендерится в задаче 03, но наполняется только в Day 12.

## Критерии готовности
- Файл компилируется (`./gradlew compileKotlin`).
- `WorkingMemory().isEmpty() == true`, `LongTermMemory().isEmpty() == true`.
- Никаких ссылок на LLM/стораж — чистые модели.

## Зависимости
Нет.
