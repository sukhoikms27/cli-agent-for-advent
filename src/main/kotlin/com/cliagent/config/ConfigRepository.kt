package com.cliagent.config

import java.io.FileInputStream
import java.util.Properties

class ConfigRepository {

    fun load(): AppConfig {
        val localProps = loadLocalProperties()

        val apiKey = System.getenv("CLI_AGENT_API_KEY")
            ?: localProps.getProperty("api.key")
            ?: error(
                "API key not found. Set CLI_AGENT_API_KEY environment variable " +
                "or add api.key=<your-key> to local.properties"
            )

        val model = System.getenv("CLI_AGENT_MODEL")
            ?: localProps.getProperty("model")
            ?: "glm-5.1"

        val baseUrl = System.getenv("CLI_AGENT_BASE_URL")
            ?: localProps.getProperty("base.url")
            ?: "https://api.z.ai/api/coding/paas/v4"

        val mcpCommand = System.getenv("CLI_AGENT_MCP_COMMAND")
            ?: localProps.getProperty("mcp.command")

        // День 18: remote Streamable HTTP. Приоритет URL > command — если задан mcpUrl, клиент
        // подключается к remote-серверу; GitHub-токен клиенту НЕ нужен (он держится на сервере).
        val mcpUrl = System.getenv("CLI_AGENT_MCP_URL")
            ?: localProps.getProperty("mcp.url")
        val mcpToken = System.getenv("CLI_AGENT_MCP_TOKEN")
            ?: localProps.getProperty("mcp.token")

        return AppConfig(
            apiKey = apiKey, model = model, baseUrl = baseUrl,
            mcpCommand = mcpCommand, mcpUrl = mcpUrl, mcpToken = mcpToken,
        )
    }

    private fun loadLocalProperties(): Properties {
        val props = Properties()
        val file = java.io.File("local.properties")
        if (file.exists()) {
            FileInputStream(file).use { props.load(it) }
        }
        return props
    }
}
