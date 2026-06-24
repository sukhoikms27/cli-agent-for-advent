package com.cliagent.mcp

import com.cliagent.llm.model.FunctionDef
import com.cliagent.llm.model.ToolDefinition
import kotlinx.serialization.json.Json

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

private val toolJson = Json { ignoreUnknownKeys = true }

/**
 * Маппинг в OpenAI-tools schema (день 17): [inputSchema] (JSON-строка сериализованной MCP
 * `ToolSchema` — `{"type":"object","properties":...,"required":...}`) парсится в `parameters`
 * как есть. MCP-схема аргументов совместима с JSON Schema, ожидаемой OpenAI `tools`.
 */
fun McpTool.toToolDefinition(): ToolDefinition {
    val parameters = inputSchema?.let { runCatching { toolJson.parseToJsonElement(it) }.getOrNull() }
    return ToolDefinition(
        function = FunctionDef(name = name, description = description, parameters = parameters)
    )
}
