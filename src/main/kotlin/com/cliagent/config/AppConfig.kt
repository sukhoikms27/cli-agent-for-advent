package com.cliagent.config

data class AppConfig(
    val apiKey: String,
    val model: String = "glm-5.1",
    val baseUrl: String = "https://api.z.ai/api/coding/paas/v4",
    /** Команда запуска MCP-сервера (stdio), день 16. null — MCP не настроен. */
    val mcpCommand: String? = null
)
