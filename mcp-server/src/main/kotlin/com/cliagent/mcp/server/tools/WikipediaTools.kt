package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.util.stringArg
import com.cliagent.mcp.server.util.toolError
import com.cliagent.mcp.server.wikipedia.WikiArticle
import com.cliagent.mcp.server.wikipedia.WikipediaClient
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Day 19 — Wikipedia tool (search-этап пайплайна tech-дайджеста). Read-only, без API-ключа.
 * Регистрирует `search_wikipedia(query, language)`: opensearch-резолв названия → REST summary →
 * plain-text extract + URL. Источник «энциклопедических» данных для [format_report].
 *
 * Tool-ошибки → [toolError] (isError=true, видны LLM), не exception (конвенция MCP, как Day 18).
 */
internal fun registerWikipediaTools(server: Server, client: WikipediaClient) {
    server.addTool(
        name = "search_wikipedia",
        description = "Найти статью в Wikipedia по теме/фразе и вернуть краткое описание (extract) " +
            "и ссылку. Используй как энциклопедический источник данных для дайджеста или отчёта. " +
            "Запрос — произвольная фраза (например 'Kotlin programming language'); статья " +
            "резолвится автоматически через opensearch.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "Тема или поисковая фраза, например 'Kotlin' или 'microservices'")
                }
                putJsonObject("language") {
                    put("type", "string")
                    put("description", "Языковой раздел Wikipedia: 'en' (по умолчанию) или 'ru'")
                    put("default", "en")
                    put("enum", buildJsonArray { add(JsonPrimitive("en")); add(JsonPrimitive("ru")) })
                }
            },
            required = listOf("query"),
        ),
    ) { req -> handleSearchWikipedia(req, client) }
}

private suspend fun handleSearchWikipedia(
    req: CallToolRequest, client: WikipediaClient,
): CallToolResult {
    val query = stringArg(req.arguments, "query")?.trim()
    if (query.isNullOrBlank()) return toolError("Параметр 'query' обязателен.")
    val language = stringArg(req.arguments, "language")?.trim()?.ifBlank { null } ?: "en"
    val article = client.search(query, language)
        ?: return toolError("Статья по запросу '$query' не найдена (язык: $language).")
    return CallToolResult(content = listOf(TextContent(formatArticle(article))), isError = false)
}

/** Текстовое представление статьи для tool-ответа (LLM читает его и передаёт в format_report). */
internal fun formatArticle(a: WikiArticle): String = buildString {
    appendLine("Wikipedia: ${a.title}")
    a.description?.takeIf { it.isNotBlank() }?.let { appendLine("Аннотация: $it") }
    appendLine("Описание: ${a.extract}")
    a.url?.let { appendLine("URL: $it") }
}.trimEnd()
