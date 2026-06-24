package com.cliagent.mcp

import com.cliagent.agent.ToolExecutor
import com.cliagent.llm.model.ToolDefinition

/**
 * Реализация [ToolExecutor] над [McpClient] (день 17).
 *
 * **Lazy-connect:** первое обращение ([definitions]/[call]) поднимает subprocess + MCP handshake;
 * соединение persistent в рамках сессии REPL и закрывается в `ChatCommand` при выходе (закрывает
 * брешь `day16-deferred.md §3` — раньше `client` не закрывался). Это avoids per-message cold-start
 * subprocess'а в agent tool-use loop.
 *
 * **Tool-ошибки** (`McpToolResult.isError`) не бросаются — возвращаются строкой-описанием, чтобы
 * LLM могла самокорректироваться (соответствует стандарту MCP: tool-ошибка видна модели).
 *
 * [factory] — DI-шов для тестов (как `handleMcp`): позволяет подсунуть фейк `McpClient`.
 */
class McpToolExecutor(
    private val command: List<String>,
    private val factory: (List<String>) -> McpClient = ::McpClient,
) : ToolExecutor {

    @Volatile
    private var client: McpClient? = null

    private suspend fun ensureConnected() {
        if (client == null) {
            val c = factory(command)
            c.connect()
            client = c
        }
    }

    override suspend fun definitions(): List<ToolDefinition> {
        ensureConnected()
        return client!!.listTools().map { it.toToolDefinition() }
    }

    override suspend fun call(name: String, args: Map<String, Any?>): String {
        ensureConnected()
        val result = client!!.callTool(name, args)
        return if (result.isError) "Tool '$name' returned an error: ${result.text}" else result.text
    }

    override suspend fun close() {
        val c = client
        client = null
        if (c != null) {
            // cleanup-путь: глотаем всё, кроме CancellationException (конвенция CLAUDE.md).
            try {
                c.close()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // close на уже упавшем соединении — игнорируем
            }
        }
    }
}
