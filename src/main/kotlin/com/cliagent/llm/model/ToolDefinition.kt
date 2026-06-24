package com.cliagent.llm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// OpenAI-compatible function-calling (день 17). z.ai/GLM принимает тот же формат `tools`/`tool_calls`.

/**
 * Декларация tool'а, отправляемая в LLM в поле `tools` запроса. [parameters] — JSON Schema
 * аргументов (маппится из `McpTool.inputSchema` — уже JSON-строка ToolSchema от MCP-сервера).
 */
@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDef,
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

/**
 * Один вызов tool'а, который LLM решила сделать (приходит в `choices[].message.tool_calls`).
 * [ToolCallFunction.arguments] — JSON-строка аргументов от LLM (парсится агентом в map).
 *
 * [type] — обязательное поле каждой записи `tool_calls` по спецификации OpenAI/GLM (`"function"`).
 * z.ai strict-валидирует его: при эхо-возврате assistant-сообщения с tool_calls в истории без `type`
 * → error 1214 "Tool type cannot be empty". encodeDefaults=true на клиентском Json обеспечивает
 * его сериализацию (даже когда LLM не вернула `type` в ответе — default "function" подставляется).
 */
@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction,
)

@Serializable
data class ToolCallFunction(
    val name: String,
    val arguments: String = "",
)
