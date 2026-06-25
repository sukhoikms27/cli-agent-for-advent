package com.cliagent.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.headers
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
 * Тонкая обёртка над MCP SDK. Два режима транспорта (день 18):
 *  - [McpTransportConfig.Stdio] — запуск subprocess-сервера по stdio (день 16–17, прежняя логика);
 *  - [McpTransportConfig.Http] — remote Streamable HTTP к серверу на VPS (день 18).
 *
 * Жизненный цикл: [connect] → [listTools]/[callTool] → [close].
 *
 * **НЕ `AutoCloseable`**: `Client.close()` — suspend, а `AutoCloseable.close()` non-suspend; `use{}`
 * неприменим из-за suspend-методов. Вызывающий код (suspend `handleMcp` / `McpToolExecutor`) вызывает
 * [close] напрямую в `finally` — без вложенного `runBlocking`.
 *
 * SDK-типы не утекают наружу: [listTools] возвращает наш [McpTool], [callTool] — [McpToolResult].
 */
class McpClient(private val transport: McpTransportConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var process: Process? = null
    private var client: Client? = null
    private var connected = false

    /**
     * Поднимает соединение и выполняет MCP handshake (initialize + initialized).
     *  - Stdio: запускает subprocess, таймаут 30с — иначе зависший сервер вешает REPL.
     *  - Http: создаёт HttpClient + StreamableHttpClientTransport, handshake 30с.
     * Idempotent: повторный вызов после успеха — no-op.
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (connected) return@withContext

        val c = Client(clientInfo = Implementation(name = "cli-agent", version = "0.1.0"))
        try {
            when (val t = transport) {
                is McpTransportConfig.Stdio -> {
                    require(t.command.isNotEmpty()) { "MCP command is empty" }
                    val proc = ProcessBuilder(t.command)
                        .redirectErrorStream(false)   // stdout несёт JSON-RPC — не смешивать с stderr
                        .start()
                    process = proc
                    // error= КРИТИЧНО: StdioClientTransport (0.13.0) читает stderr subprocess только если
                    // он передан; иначе OS pipe-буфер (~64КБ) переполняется (npx first-run пишет прогресс
                    // в stderr) → сервер блокируется на stderr.write → connect() виснет (pipe-deadlock).
                    val clientTransport = StdioClientTransport(
                        input = proc.inputStream.asSource().buffered(),
                        output = proc.outputStream.asSink().buffered(),
                        error = proc.errorStream.asSource().buffered(),
                    )
                    withTimeout(30.seconds) { c.connect(clientTransport) }
                }

                is McpTransportConfig.Http -> {
                    // HttpClient (CIO) — общий для transport и SSE-стримов внутри него. Bearer-токен
                    // добавляется в requestBuilder на каждый запрос (auth-header).
                    val http = HttpClient(CIO)
                    val clientTransport = StreamableHttpClientTransport(
                        client = http,
                        url = t.url,
                        requestBuilder = {
                            headers { append("Authorization", "Bearer ${t.token}") }
                        },
                    )
                    withTimeout(30.seconds) { c.connect(clientTransport) }
                }
            }
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

    /**
     * Вызывает инструмент сервера (день 17). [arguments] — map аргументов (значения — примитивы
     * или JsonElement; SDK конвертирует в JsonObject). Возвращает доменный [McpToolResult]:
     * текст склеен из `TextContent`-блоков, `isError` — из `CallToolResult.isError`.
     *
     * Tool-ошибка (`isError=true`) **не бросается** — отдаётся агенту, чтобы LLM могла
     * самокорректироваться. Протокольная ошибка (timeout, tool не найден) — exception, ловится
     * выше (`handleMcp` / agent loop).
     */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): McpToolResult {
        val c = client ?: error("McpClient not connected — call connect() first")
        val result = withTimeout(30.seconds) { c.callTool(name = name, arguments = arguments) }
        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        return McpToolResult(text = text, isError = result.isError == true)
    }

    /** Graceful shutdown JSON-RPC, затем (для stdio) надёжное убийство subprocess. suspend — НЕ AutoCloseable. */
    suspend fun close() {
        connected = false
        // СНАЧАЛА stopProcess: SDK Client.close() → transport.closeResources() делает scope.join(),
        // ожидая EOF reader'а по stdin → а EOF наступает только когда subprocess умер. Если звать
        // close() до stopProcess — дедлок (join ждёт смерти процесса, stopProcess после close).
        // Убив процесс сначала → reader получает EOF → scope завершается → close() отрабатывает быстро.
        // Для Http-транспорта process == null → stopProcess no-op; HttpClient закрывается внутри
        // transport.closeResources() SDK.
        stopProcess()
        try {
            withTimeout(5.seconds) { client?.close() }
        } catch (e: TimeoutCancellationException) {
            // мало ли — fallback уже отработал (процесс убит)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            // cleanup-путь: глотаем всё, кроме cancellation
        }
        client = null
        process = null
    }

    /**
     * destroy (SIGTERM) → waitFor(2с) → destroyForcibly. Сначала убиваются descendant'ы:
     * `npx` порождает `node`-сервер как дочерний процесс — убийство только `npx` оставляет
     * node-ребёнка сиротой (orphan), копящийся от вызова к вызову. `ProcessHandle.descendants()`
     * покрывает всё дерево (JDK 9+). No-op для Http-транспорта (process == null).
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
