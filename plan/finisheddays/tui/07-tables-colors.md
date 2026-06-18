# Задача 07. Таблицы mordant + `--no-color`

## Цель
`/stats`/`/cost` — настоящие таблицы mordant; цветные префиксы; флаг `--no-color`.

## Файлы (правка)
- `src/main/kotlin/com/cliagent/cli/ChatCommand.kt` — `printStats`/`printCost` → `AppTerminal.t.table { ... }`; цветные `ok`/`err`/`warn` префиксы в остальных выводах.
- `--no-color` флаг clikt → конфигурирует `AppTerminal.t`.

## Что изменить
1. `printStats`: mordant `table { header(...); row(...) }` (Requests, Prompt, Completion, Total, Cached, History est, Strategy).
2. `printCost`: таблица (Model, Input, Output, Total, Cached saved).
3. Флаг:
   ```kotlin
   private val noColor by option("--no-color", help = "Disable colored output").flag()
   ```
   В `run()`: если `noColor` — `AppTerminal` переключить в plain (mordant: `Terminal(noTerm = true)` или `t.update { ... }` — проверить API).
4. Цветные префиксы: `green("✓")`, `red("Error: ")`, `yellow("⚠️ ")` в успехе/ошибках/warnings.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `/stats`/`/cost` — выровненные таблицы; цвета в TTY; `--no-color` → plain.

## Зависимости
Задача 05.
