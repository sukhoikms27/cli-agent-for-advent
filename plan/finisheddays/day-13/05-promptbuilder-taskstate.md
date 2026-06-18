# Задача 05. Рендер Task state в PromptBuilder

## Цель
Состояние задачи инжектится в system prompt каждого запроса → агент осведомлён о стадии и
продолжается без повторных объяснений (ключевое требование «resume without re-explanation»).

## Файл (правка)
`src/main/kotlin/com/cliagent/agent/PromptBuilder.kt`

## Что изменить

### 1. В `WorkingMemory.renderBlock()` — после блока `taskDecisions`
```kotlin
taskState?.let { ts ->
    lines.add("Task state:")
    lines.add("  Stage: ${ts.stage.name.lowercase()}")
    ts.currentStep?.let { lines.add("  Current step: $it") }
    ts.expectedAction?.let { lines.add("  Expected action: $it") }
    ts.approvedPlan?.let { lines.add("  Approved plan: $it") }
}
```

### 2. Обновить doc-комментарий класса (убрать «получит поле» — теперь оно есть)
```
 * Точки расширения:
 *  - Day 12: LongTermMemory.profile рендерится автоматически (см. UserProfile.renderBlock).
 *  - Day 13: WorkingMemory.taskState рендерится в WorkingMemory.renderBlock (блок Task state).
```

## Что НЕ меняется
- Сигнатура `PromptBuilder` и `build()` — состояние течёт через `WorkingMemory`.
- Пустой `taskState` (null) → блок не рендерится (элизия пустых слоёв сохраняется).

## Конвенции
- `stage.name.lowercase()` → `execution`, не `EXECUTION` (консистентно с существующими
  render-блоками: «Task:», «Plan:», «Notes:»).

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `WorkingMemory(taskState = TaskState(stage = EXECUTION, currentStep = "s"))` → built content
  содержит `Task state:`, `Stage: execution`, `Current step: s`.
- `WorkingMemory(currentTask = "x")` (taskState=null) → НЕ содержит `Task state:`.

## Зависимости
Задача 04.
