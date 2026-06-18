# Задача 01. Зависимости JLine3 + mordant

## Цель
Подключить TUI-зависимости, проверить резолв и компиляцию.

## Файл (правка)
`build.gradle.kts`

## Что добавить
```kotlin
// mordant — terminal output (цвета, таблицы, спиннеры). Версия 2.5.0 — та, что clikt 4.4.0
// тянет транзитивно; объявляем явно для прямого использования API.
implementation("com.github.ajalt.mordant:mordant:2.5.0")

// JLine3 — REPL input (история, completion, редактирование, сигналы).
// org.jline:jline:3.29.0 — единый fat-jar (содержит reader/terminal/builtins/completer).
implementation("org.jline:jline:3.29.0")
```

## Проверено
- Резолв: `org.jline:jline:3.29.0` (fat-jar, классы `LineReader`/`LineReaderBuilder`/`TerminalBuilder`/`EndOfFileException`/`UserInterruptException` присутствуют).
- mordant 2.5.0: `Terminal`, `table`, `rendering.TextColors`, `animation` (progress/spinner) — на classpath.
- `./gradlew compileKotlin` — EXIT=0.

## Критерии готовности
- `./gradlew dependencies` (или compileKotlin) резолвит обе зависимости без ошибок.
- `./gradlew compileKotlin` — собирается (новых импортов пока нет, просто депсы на classpath).

## Зависимости
Нет.
