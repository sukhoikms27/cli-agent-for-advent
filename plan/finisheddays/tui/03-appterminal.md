# Задача 03. AppTerminal — mordant-обёртка вывода

## Цель
Единая точка терминального вывода: цвета, таблицы, спиннеры. Замена clikt `echo` / `println`.

## Файл (новый)
`src/main/kotlin/com/cliagent/cli/AppTerminal.kt`

## Что реализовать
```kotlin
package com.cliagent.cli

import com.github.ajalt.mordant.Terminal

object AppTerminal {
    val t: Terminal = Terminal()   // auto-detect цвета; --no-color через t.update { ... }
    fun println(text: Any?) = t.println(text)
    fun print(text: Any?) = t.print(text)
}
```
- Helper-функции/расширения по мере надобности: `ok(msg)` = green("✓") + msg, `err(msg)` = red("Error: ") + msg, `warn(msg)` = yellow("⚠️ ") + msg.
- mordant `Terminal()` автоопределяет TTY/цвета; `--no-color` обрабатывается в задаче 07 (флаг clikt → `Terminal(noTerm = ...)` или `t.update`).

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- `AppTerminal.println("x")` работает; цвета автоопределяются.

## Зависимости
Задача 01.
