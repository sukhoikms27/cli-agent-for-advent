package com.cliagent.agent

import com.cliagent.llm.model.ToolDefinition

/**
 * Seam для вызова внешних tools из агента (день 17). Изолирует agent-слой от MCP SDK: реализация
 * [com.cliagent.mcp.McpToolExecutor] живёт в `mcp/`. Null-инъекция (`toolExecutor = null`) — tools
 * отключены, поведение дней 1–16 не меняется (нет MCP-сервера / `mcpCommand` не задан).
 *
 * [definitions] возвращает схемы tools для поля `tools` LLM-запроса; [call] исполняет tool и
 * возвращает текстовый результат (включая человекочитаемое описание tool-ошибки, не бросая).
 */
interface ToolExecutor {
    suspend fun definitions(): List<ToolDefinition>
    suspend fun call(name: String, args: Map<String, Any?>): String
    suspend fun close()
}
