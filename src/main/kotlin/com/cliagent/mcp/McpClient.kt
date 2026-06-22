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
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Тонкая обёртка над MCP SDK (день 16): запуск subprocess-сервера по stdio, handshake ([connect]),
 * [listTools]. Жизненный цикл: [connect] → [listTools] → [close].
 *
 * **НЕ `AutoCloseable`**: `Client.close()` — suspend, а `AutoCloseable.close()` non-suspend; `use{}`
 * неприменим из-за suspend-методов. Вызывающий код (suspend `handleMcp`) вызывает [close] напрямую
 * в `finally` — без вложенного `runBlocking`.
 *
 * Токенизация командной строки (String → `List<String>`) выполняется вызывающей стороной.
 * SDK-типы не утекают наружу: [listTools] возвращает наш [McpTool].
 */
class McpClient(private val command: List<String>) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var process: Process? = null
    private var client: Client? = null
    private var connected = false

    /**
     * Запускает subprocess и выполняет MCP handshake (initialize + initialized).
     * Таймаут 30с — иначе зависший сервер вешает REPL (Ctrl+C не отменяет `runBlocking`).
     * Idempotent: повторный вызов после успеха — no-op.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (connected) return@withContext
        require(command.isNotEmpty()) { "MCP command is empty" }

        val proc = ProcessBuilder(command)
            .redirectErrorStream(false)   // stdout несёт JSON-RPC — не смешивать с stderr сервера
            .start()
        process = proc

        // error= КРИТИЧНО: StdioClientTransport (0.13.0) читает stderr subprocess только если он
        // передан; иначе OS pipe-буфер (~64КБ) переполняется (npx first-run пишет прогресс в stderr)
        // → сервер блокируется на stderr.write → connect() виснет (pipe-deadlock).
        val transport = StdioClientTransport(
            input = proc.inputStream.asSource().buffered(),
            output = proc.outputStream.asSink().buffered(),
            error = proc.errorStream.asSource().buffered(),
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

    /** Возвращает список инструментов сервера. Требует предварительного [connect]. */
    suspend fun listTools(): List<McpTool> {
        val c = client ?: error("McpClient not connected — call connect() first")
        val result = withTimeout(30.seconds) { c.listTools() }
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
            // cleanup-путь: глотаем всё, кроме cancellation
        }
        stopProcess()
        client = null
        process = null
    }

    /**
     * destroy (SIGTERM) → waitFor(2с) → destroyForcibly. Сначала убиваются descendant'ы:
     * `npx` порождает `node`-сервер как дочерний процесс — убийство только `npx` оставляет
     * node-ребёнка сиротой (orphan), копящийся от вызова к вызову. `ProcessHandle.descendants()`
     * покрывает всё дерево (JDK 9+).
     */
    private fun stopProcess() {
        val p = process ?: return
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
        p.destroy()
        if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
    }

    private fun Tool.toMcpTool(): McpTool = McpTool(
        name = name,
        description = description,
        inputSchema = runCatching {
            json.encodeToString(ToolSchema.serializer(), inputSchema)
        }.getOrNull(),
    )
}
