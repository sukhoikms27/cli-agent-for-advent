# T7 — Тесты `/mcp`

## Цель
Покрыть конфиг-ветки и путь исключений `handleMcp` без реального MCP-сервера (последний — ручная
верификация T8).

## Контекст
`ChatCommand()` инстанцируется без clikt-аргументов — все `option` имеют `.default` (строки 45-62),
`run()` не вызывается без `.main()`. Существующий паттерн — `ChatCommandFreeTextTest` зовёт
`cmd.dispatchFreeText(...)` напрямую. `handleMcp` — `internal suspend` (тот же шов). mordant non-TTY
в тестах → no-op для спиннеров (паттерн `MarkdownRenderTest`).

## Изменения

### `src/test/kotlin/com/cliagent/cli/McpCommandTest.kt` (новый)
```kotlin
package com.cliagent.cli

import com.cliagent.mcp.McpClient
import com.cliagent.mcp.McpException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class McpCommandTest {
    private val cmd = ChatCommand()

    @Test
    fun `mcp with no args and no config prints status`() = runTest {
        cmd.handleMcp("/mcp", mcpCommand = null)
    }

    @Test
    fun `mcp list-tools without configured command warns and returns early`() = runTest {
        cmd.handleMcp("/mcp list-tools", mcpCommand = null)
        // early-return до создания McpClient
    }

    @Test
    fun `mcp unknown subcommand prints usage`() = runTest {
        cmd.handleMcp("/mcp frobnicate", mcpCommand = null)
    }

    @Test
    fun `mcp list-tools with failing factory prints error and stays alive`() = runTest {
        // Фабрика, бросающая сразу — имитирует сбой subprocess/connect, REPL не должен упасть.
        val failing: (List<String>) -> McpClient = { throw McpException("boom") }
        cmd.handleMcp("/mcp list-tools some-cmd", mcpCommand = null, mcpClientFactory = failing)
    }
}
```

Примечание: 4-й тест передаёт `mcpCommand = null` + inline override `some-cmd` → `handleMcp` берёт
команду из `parts.drop(2)` (override), вызывает фабрику → `McpException("boom")` ловится в
`catch (e: Exception)` → `AppTerminal.err(...)`, REPL жив. Это покрывает путь исключений без
реального subprocess.

## Verify
`./gradlew test` — зелёный (новые 4 + все существующие).

## Коммит
`test: add McpCommandTest for /mcp config branches + error path (day16 T7)`.
