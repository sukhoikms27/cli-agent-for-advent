# Задача 06. Спиннер во время LLM-запроса

## Цель
Показывать спиннер, пока ждём `agent.chat()` (сейчас UI висит до 60с).

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — ветка `else -> agent.chat(input)`.

## Что изменить
```kotlin
else -> {
    val response = withSpinner("Thinking…") { agent.chat(input) }
    AppTerminal.println("\n$response\n")
}
```
Реализация `withSpinner` (в `AppTerminal` или helper):
```kotlin
suspend fun <T> withSpinner(label: String, block: suspend () -> T): T = coroutineScope {
    val job = launch { AppTerminal.t.spinner(label) /* или mordant spinner API */ }
    try { block() } finally { job.cancel() }
}
```
- `CancellationException` — НЕ глотать (finally + rethrow стандартно).
- mordant имеет `terminal.spinner { }` / progress API — использовать его; если API отличается, минимальный ручной спиннер через `launch` + `delay`.
- Аналогично для `/profile extract` (его LLM-вызов).

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- (Интерактивно) во время ответа LLM крутится спиннер, по завершении исчезает, ответ выводится.

## Зависимости
Задача 05.
