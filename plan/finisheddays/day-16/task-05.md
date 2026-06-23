# T5 — McpClient

## Цель
Обёртка над MCP SDK: запуск subprocess-сервера по stdio, handshake (`connect`), `listTools`,
корректный teardown. Жизненный цикл: `connect()` → `listTools()` → `close()`.

## Дизайн-решения (верифицированы)
- **НЕ `AutoCloseable`**: `Client.close()` — suspend, а `AutoCloseable.close()` non-suspend; `use{}`
  неприменим из-за suspend-методов. `handleMcp` (T6) сам suspend → вызывает `close()` напрямую в
  `finally`, без вложенного `runBlocking`.
- **`error=` транспорта обязателен**: `StdioClientTransport` (0.13.0) читает stderr subprocess только
  если передан `error`; иначе pipe-deadlock при >64KB stderr (первый запуск `npx`). Проверено по raw-исходникам SDK.
- **Таймауты**: `withTimeout(30s)` на `connect()`/`listTools()` — паттерн тестов самого SDK
  (`StdioClientTransportTest` использует `withTimeout` на `connect`). Без этого зависший сервер вешает REPL.
- **`stopProcess`**: `destroy()` → `waitFor(2s)` → `destroyForcibly()` — паттерн `stopProcess` SDK.

## Изменения

### `src/main/kotlin/com/cliagent/mcp/McpClient.kt` (новый)
```kotlin
package com.cliagent.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class McpClient(private val command: List<String>) {
    private var process: Process? = null
    private var client: Client? = null
    private var connected = false

    /** Запуск subprocess + MCP handshake. Таймаут — иначе зависший сервер вешает REPL. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (connected) return@withContext
        require(command.isNotEmpty()) { "MCP command is empty" }
        val proc = ProcessBuilder(command)
            .redirectErrorStream(false)            // stdout несёт JSON-RPC — не смешивать
            .start()
        process = proc
        val transport = StdioClientTransport(
            input  = proc.inputStream.asSource().buffered(),
            output = proc.outputStream.asSink().buffered(),
            error  = proc.errorStream.asSource().buffered(),   // КРИТИЧНО: иначе pipe-deadlock
        )
        val c = Client(clientInfo = Implementation(name = "cli-agent", version = "0.1.0"))
        try {
            withTimeout(30.seconds) { c.connect(transport) }   // suspend handshake
        } catch (e: TimeoutCancellationException) {
            runCatching { c.close() }
            stopProcess()
            throw McpException("MCP server did not respond within 30s")
        }
        client = c
        connected = true
    }

    suspend fun listTools(): List<McpTool> {
        val c = client ?: error("McpClient not connected — call connect() first")
        val result = withTimeout(30.seconds) { c.listTools() }   // ListToolsResult
        return result.tools.map { it.toMcpTool() }
    }

    /** Graceful shutdown JSON-RPC, затем надёжное убийство subprocess. suspend — НЕ AutoCloseable. */
    suspend fun close() {
        connected = false
        // НЕ runCatching (глотает CancellationException) — явный catch с rethrow:
        try {
            client?.close()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // cleanup — глотаем всё кроме cancellation
        }
        stopProcess()
        client = null
        process = null
    }

    private fun stopProcess() {
        val p = process ?: return
        p.destroy()                                              // SIGTERM
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly() // паттерн stopProcess SDK
    }

    private fun Tool.toMcpTool(): McpTool = McpTool(
        name = name,
        description = description,
        inputSchema = runCatching {
            McpJson.encodeToString(ToolSchema.serializer(), inputSchema)
        }.getOrNull(),
    )
}
```

### `McpJson` — единый `Json`
Если в проекте уже есть общий `AppJson` (см. CLAUDE.md «единый Json-инстанс») — использовать его.
Если нет — создать `mcp/McpJson.kt` (`object McpJson : Json by Json { ignoreUnknownKeys=true; encodeDefaults=true; explicitNulls=false }`)
или локальный `Json` как в `OpenAiCompatibleClient.kt:22-26`. **Проверить перед написанием** —
найти общий Json в проекте через `ast-index symbol AppJson` / grep `object.*Json`.

## Verify
1. Компилируется (`./gradlew compileKotlin`).
2. Сигнатуры SDK соответствуют IDE-проверке из T2; при расхождении — адаптировать (форма
   `Client`/`Implementation`, тип `Tool.inputSchema`, существование `ToolSchema.serializer()`).

## Коммит
`feat: add McpClient (stdio transport, withTimeout, suspend close, stopProcess) (day16 T5)`.
