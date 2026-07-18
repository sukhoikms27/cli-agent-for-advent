package com.cliagent.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import com.cliagent.mcp.server.notes.NotesStore
import com.cliagent.mcp.server.tools.registerFileAgentTools
import com.cliagent.mcp.server.tools.registerGitHubTools
import com.cliagent.mcp.server.tools.registerNotesTools
import com.cliagent.mcp.server.tools.registerProjectTools
import com.cliagent.mcp.server.tools.registerWeatherTools
import com.cliagent.mcp.server.tools.registerWikipediaTools
import com.cliagent.mcp.server.weather.WeatherClient
import com.cliagent.mcp.server.weather.WeatherScheduler
import com.cliagent.mcp.server.weather.WeatherStore
import com.cliagent.mcp.server.wikipedia.WikipediaClient
import java.io.File

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
 * @param wikipediaClient энциклопедический источник (Day 19 — search-этап пайплайна)
 * @param notesStore каталог заметок/отчётов (Day 19 — save-этап пайплайна)
 */
internal fun buildServer(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    weatherScheduler: WeatherScheduler,
    wikipediaClient: WikipediaClient,
    notesStore: NotesStore,
): Server = buildServer(
    githubToken, weatherClient, weatherStore, weatherScheduler, wikipediaClient, notesStore,
    fileAgentRoot = null, fileAgentConfirmWrite = null,
)

/**
 * День 34 — расширенная фабрика с file-agent tools.
 *
 * @param fileAgentRoot sandbox для file-операций; null = file tools не регистрируются (backward-compat
 *        с днями 18-33, http-сервер без file-write). В CLI file-agent прокидывается CWD проекта.
 * @param fileAgentConfirmWrite callback подтверждения write-операции (Human-in-the-Loop, лекция нед.7).
 *        null = write_file в read-only fail-safe режиме (только для http-сервера / batch).
 */
@Suppress("LongParameterList")
internal fun buildServer(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    weatherScheduler: WeatherScheduler,
    wikipediaClient: WikipediaClient,
    notesStore: NotesStore,
    fileAgentRoot: File?,
    fileAgentConfirmWrite: (suspend (path: String, content: String) -> Boolean)?,
): Server {
    val server = Server(
        serverInfo = Implementation(name = "cli-agent-mcp", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
        ),
    )
    registerGitHubTools(server, githubToken)
    registerWeatherTools(server, weatherClient, weatherStore, weatherScheduler)
    registerWikipediaTools(server, wikipediaClient)
    registerNotesTools(server, notesStore)
    // День 31: read-only project/git tools (stateless — без новых зависимостей в сигнатуре).
    registerProjectTools(server)
    // День 34: file-agent tools (read/find/list/write с dangerous-ops guard).
    // Регистрируются только если задан fileAgentRoot (CLI file-agent mode). http-сервер без них.
    if (fileAgentRoot != null) {
        registerFileAgentTools(server, root = fileAgentRoot, confirmWrite = fileAgentConfirmWrite)
    }
    return server
}
