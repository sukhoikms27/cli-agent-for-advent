# Задача 05. ChatCommand: проводка ReplEngine + AppTerminal

## Цель
`ChatCommand.run()` использует `ReplEngine` для ввода и `AppTerminal` для вывода; stray `println` убран.

## Файл (правка)
`src/main/kotlin/com/cliagent/cli/ChatCommand.kt`

## Что изменить
1. REPL-цикл: убрать `BufferedReader(InputStreamReader(System.`in`))` + `readLine()`.
   ```kotlin
   val repl = ReplEngine()
   while (true) {
       val input = repl.readLine() ?: break   // null = Ctrl+D
       if (input.isBlank()) continue
       when { /* существующий диспетч */ }
   }
   ```
   Диспетч `when` (slash-команды + `else -> agent.chat`) сохраняется.
2. Вывод: `echo(...)` → `AppTerminal.println(...)` во всех `printX`/`handleX` (поэтапно; минимум — баннер, промпты, ошибки, результаты). `echo("> ", trailingNewline=false)` уходит (промпт теперь в JLine `readLine("cli-agent> ")`).
3. Stray `println`:
   - `ContextAwareAgent.kt:77/82/91` (compressing/warning) → `AppTerminal.println`.
   - `OpenAiCompatibleClient.kt:53` (debug) → `AppTerminal.println` или `System.err` оставить.
   - `ChatCommand.kt:148` (unknown strategy) → `AppTerminal.println`.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- REPL запускается через ReplEngine; slash-команды работают как прежде.
- `echo`/`BufferedReader` из ChatCommand убраны.

## Зависимости
Задачи 03, 04.
