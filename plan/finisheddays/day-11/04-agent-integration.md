# Задача 04. Интеграция слоёв в ContextAwareAgent

## Цель
Агент загружает рабочую и долговременную память и подмешивает их в system prompt через `PromptBuilder`. Точка интеграции — `buildMessagesToSend`.

## Файл (правка)
`src/main/kotlin/com/cliagent/agent/ContextAwareAgent.kt`

## Что изменить

### 1. Поля
```kotlin
private var workingMemory: WorkingMemory? = null
private var longTermMemory: LongTermMemory? = null
```

### 2. `ensureLoaded()` — после загрузки history
```kotlin
workingMemory = memoryStore.loadWorkingMemory(chatId)
longTermMemory = memoryStore.loadLongTermMemory()
```
Long-term грузится раз за сессию (guarded by `loaded`), не на каждый ход.

### 3. `buildMessagesToSend` — заменить вычисление `system`
Было:
```kotlin
val system = if (reasoningStrategy != null) {
    PromptTemplates.buildSystemMessage(reasoningStrategy)
} else {
    systemPrompt
}
```
Стало:
```kotlin
val baseSystem = if (reasoningStrategy != null) {
    PromptTemplates.buildSystemMessage(reasoningStrategy)
} else {
    systemPrompt
}
val system = PromptBuilder(baseSystem, longTermMemory, workingMemory).build()
```
Обе ветки (contextManager и legacy) используют этот `system` без изменений. Инъекция summary-as-system-message (строки 121-124, 135-138) остаётся — это отдельный артефакт краткосрочного сжатия.

### 4. `reset()` — добавить очистку рабочей памяти
```kotlin
memoryStore.clearWorkingMemory(chatId)
workingMemory = null
```
Long-term НЕ чистим (global).

### 5. Аксессоры для `/memory`
```kotlin
suspend fun getWorkingMemory(): WorkingMemory? =
    workingMemory ?: memoryStore.loadWorkingMemory(chatId)
suspend fun getLongTermMemory(): LongTermMemory =
    longTermMemory ?: memoryStore.loadLongTermMemory()
suspend fun setWorkingMemory(w: WorkingMemory) {
    memoryStore.saveWorkingMemory(chatId, w); workingMemory = w
}
suspend fun setLongTermMemory(lt: LongTermMemory) {
    memoryStore.saveLongTermMemory(lt); longTermMemory = lt
}
```
Агент = единственный оркестратор (как `handleStrategy`/`handleBranch`), `ChatCommand` не трогает стор напрямую.

## Инвариант совместимости
Когда `workingMemory == null` и `longTermMemory` пуст → `PromptBuilder.build()` возвращает базовый контент → список сообщений байт-идентичен дням 1–10.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- Существующий путь `chat()` без памяти работает как раньше.
- `/reset` очищает working (но не long-term).

## Зависимости
Задача 02 (стораж), задача 03 (PromptBuilder).
