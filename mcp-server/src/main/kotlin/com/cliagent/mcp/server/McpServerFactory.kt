package com.cliagent.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import com.cliagent.mcp.server.tools.registerGitHubTools
import com.cliagent.mcp.server.tools.registerWeatherTools
import com.cliagent.mcp.server.weather.WeatherClient
import com.cliagent.mcp.server.weather.WeatherScheduler
import com.cliagent.mcp.server.weather.WeatherStore

/**
 * Фабрика MCP-сервера (Day 18 — точка расширения): создаёт Server и регистрирует ВСЕ tools.
 * Используется ОБОИМИ режимами (stdio — один Server; http — новый Server на соединение, см.
 * `McpServerApp.runHttp` → `mcpStreamableHttp` → `block = { buildServer(...) }`).
 *
 * Добавление tool = регистрация здесь (+ опц. новые зависимости в сигнатуре).
 *
 * @param githubToken PAT для `get_repo` (Day 17); null → tool-error при вызове
 * @param weatherClient источник погодных данных (Open-Meteo, Day 18)
 * @param weatherStore персистентное хранилище снапшотов (Day 18)
 * @param weatherScheduler динамический реестр подписок на periodic-сбор (Day 18 redesign, R1)
 */
internal fun buildServer(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    weatherScheduler: WeatherScheduler,
): Server {
    val server = Server(
        serverInfo = Implementation(name = "cli-agent-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
        ),
    )
    registerGitHubTools(server, githubToken)
    registerWeatherTools(server, weatherClient, weatherStore, weatherScheduler)
    return server
}
