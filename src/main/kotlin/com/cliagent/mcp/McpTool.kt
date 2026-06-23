package com.cliagent.mcp

/**
 * Наше представление MCP-инструмента (день 16). Изолирует CLI-слой от типов MCP SDK:
 * [inputSchema] хранится как JSON-строка (как есть от сервера), чтобы не тянуть
 * `ToolSchema`/`JsonElement` в сигнатуры CLI. День 16 отображает только name + description.
 */
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: String?
)
