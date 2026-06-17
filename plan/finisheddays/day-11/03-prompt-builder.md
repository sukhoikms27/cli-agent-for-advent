# Задача 03. PromptBuilder — слоёный system prompt

## Цель
Абстракция prompt builder из лекции: композировать system prompt из базового промпта + блоков long-term и working памяти. Пустые слои элизируются → поведение дней 1–10 не меняется.

## Файл (новый)
`src/main/kotlin/com/cliagent/agent/PromptBuilder.kt`

## Что реализовать
```kotlin
package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage
import com.cliagent.memory.LongTermMemory
import com.cliagent.memory.WorkingMemory
import com.cliagent.memory.UserProfile

class PromptBuilder(
    private val baseSystem: ChatMessage,
    private val longTerm: LongTermMemory?,
    private val working: WorkingMemory?
) {
    fun build(): ChatMessage {
        val parts = mutableListOf(baseSystem.content)
        longTerm?.takeIf { !it.isEmpty() }?.let { parts.add(it.renderBlock()) }
        working?.takeIf { !it.isEmpty() }?.let { parts.add(it.renderBlock()) }
        return baseSystem.copy(content = parts.joinToString("\n\n"))
    }
}
```

`renderBlock()` — internal extension-функции (можно в этом же файле или в `MemoryLayer.kt`):
- `LongTermMemory.renderBlock(): String` — секции `[Long-term knowledge]`, `[Long-term decisions]`, `[User profile]` (когда `profile != null`, Day 12). Пустые карты/поля не рисуются.
- `WorkingMemory.renderBlock(): String` — секция `[Working memory — current task]` с task/plan/notes/decisions (только непустые).
- `UserProfile.renderBlock()` (или inline) — стиль/формат/ограничения.

## Детали
- `ChatMessage.copy(content = ...)` сохраняет исходный `id` — id system-сообщения для ветвления не нужен, ок.
- Когда оба слоя пусты → `build()` возвращает `baseSystem` с неизменным контентом → список сообщений байт-идентичен сегодняшнему.
- Порядок блоков: base → long-term → working (долговременный контекст «весомее», рабочая задача — ближе к запросу).

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `PromptBuilder(base, null, null).build().content == base.content`.
- `PromptBuilder(base, LongTermMemory(knowledge=mapOf("stack" to "Kotlin")), null).build().content` содержит `[Long-term knowledge]` и `Kotlin`, не содержит `[Working memory`.

## Зависимости
Задача 01 (модели). Тестируется в задаче 06.
