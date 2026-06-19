# Задача 12 (доработка Day 13). StagePromptTemplates — stage-enforcing промпты

## Контекст
Доработка Day 13 по комментариям автора курса (Вариант 2). Базовый system prompt зависит от
стадии → single-session агент ведёт себя per stage (clarify уточняет, execution пишет код,
validation проверяет).

## Файл (новый)
`src/main/kotlin/com/cliagent/llm/model/StagePromptTemplates.kt`

## Что реализовать
`object` (зеркало `PromptTemplates`), `when (stage)` exhaustive по 5 стадиям, возвращает
`ChatMessage(role = "system", content = ...)`:
- `CLARIFY` — только уточнять требования, задавать вопросы, НЕ предлагать решение/код.
- `PLANNING` — конкретный пошаговый план, НЕ писать код реализации.
- `EXECUTION` — реализация по утверждённому плану, рабочий код, фиксировать решения, не перепланировать.
- `VALIDATION` — проверить результат против плана/constraints, НЕ добавлять фичи, вердикт pass/rework.
- `DONE` — задача завершена, подвести итоги, не начинать новую работу.

Промпты на английском (консистентно с `SystemPrompts`/`PromptTemplates`). `import com.cliagent.state.TaskStage`.

## Конвенции
- `object`, `fun buildSystemMessage(stage: TaskStage): ChatMessage` — зеркало
  `PromptTemplates.buildSystemMessage(ReasoningStrategy)` (`llm/model/PromptTemplates.kt`).
- `when` exhaustive — компилятор заставит покрыть все 5 стадий.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `buildSystemMessage` покрывает все 5 стадий.

## Зависимости
—
