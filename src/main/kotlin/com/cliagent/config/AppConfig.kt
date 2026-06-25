package com.cliagent.config

data class AppConfig(
    val apiKey: String,
    val model: String = "glm-5.1",
    val baseUrl: String = "https://api.z.ai/api/coding/paas/v4",
    /** Команда запуска MCP-сервера (stdio), день 16. null — stdio-MCP не настроен. */
    val mcpCommand: String? = null,
    /** URL remote MCP-сервера (Streamable HTTP, день 18 — деплой на VPS). null — remote-MCP не настроен. */
    val mcpUrl: String? = null,
    /** Bearer-токен для remote MCP-сервера (день 18). Применяется только при заданном [mcpUrl]. */
    val mcpToken: String? = null,
)
