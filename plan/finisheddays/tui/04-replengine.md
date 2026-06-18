# Задача 04. ReplEngine — JLine3 REPL-цикл

## Цель
Заменить `BufferedReader.readLine()` на JLine `LineReader`: история, completion, корректные Ctrl+C/D.

## Файл (новый)
`src/main/kotlin/com/cliagent/cli/ReplEngine.kt`

## Что реализовать
```kotlin
package com.cliagent.cli

import com.cliagent.config.AppPaths
import org.jline.reader.*
import org.jline.terminal.TerminalBuilder

class ReplEngine {
    private val terminal = TerminalBuilder.builder().system(true).build()
    private val reader: LineReader = LineReaderBuilder.builder()
        .terminal(terminal)
        .completer(buildCompleter())
        .variable(LineReader.HISTORY_FILE, AppPaths.replHistoryFile.toString())
        .build()

    /** Один шаг REPL. Возвращает строку ввода; null = выход (Ctrl+D). */
    fun readLine(): String? = try {
        reader.readLine("cli-agent> ")?.takeIf { it.isNotBlank() }
    } catch (e: UserInterruptException) {
        ""        // Ctrl+C — отменить ввод, продолжить REPL
    } catch (e: EndOfFileException) {
        null      // Ctrl+D — выход
    }

    private fun buildCompleter(): Completer {
        val top = StringsCompleter(
            "/exit", "/help", "/history", "/chats", "/stats", "/cost",
            "/summary", "/compress", "/facts", "/reset", "/strategy", "/branch", "/memory", "/profile"
        )
        // Подкоманды (опционально, через ArgumentCompleter)
        return AggregateCompleter(top)
    }
}
```
- `readLine()` возвращает `""` для пустого/Ctrl+C (вызов continue в цикле), `null` для выхода.
- Completion: top-level slash-команды; `ArgumentCompleter` для подкоманд `/strategy|/branch|/memory|/profile` — добавить при необходимости.

## Критерии готовности
- `./gradlew compileKotlin` собирается.
- (Интерактивно, задача 08) стрелки/история/Ctrl+C/D работают.

## Зависимости
Задача 02.
