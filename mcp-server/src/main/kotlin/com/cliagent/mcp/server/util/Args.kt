package com.cliagent.mcp.server.util

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Общие helpers для MCP-tool'ов (Day 18 — вынесены из мономолита GitHubMcpServer).
 *
 * - [toolError] — tool-level error по стандарту MCP (isError=true, видна LLM), не exception.
 * - [stringArg] / [numberArg] — извлечение типизированных аргументов из JsonObject `arguments`.
 * - [NAME_REGEX] — allowlist для GitHub owner/repo (path-injection guard, Day 17).
 */

/** Tool-level error (isError=true) — по стандарту MCP не бросается exception'ом, а видна LLM. */
internal fun toolError(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

/** Строковый аргумент из JsonObject arguments; null если отсутствует/не строка. */
internal fun stringArg(args: JsonObject?, key: String): String? {
    val el = args?.get(key) ?: return null
    val prim = el as? JsonPrimitive ?: return null
    return if (prim.isString) prim.content else null
}

/** Числовой аргумент (int/double) из JsonObject; null если отсутствует/не число. */
internal fun numberArg(args: JsonObject?, key: String): Double? =
    (args?.get(key) as? JsonPrimitive)?.doubleOrNull

/** Допустимые символы для owner/repo в path-сегменте GitHub URL (path-injection guard). */
internal val NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")
