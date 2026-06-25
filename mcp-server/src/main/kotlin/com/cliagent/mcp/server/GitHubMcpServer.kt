package com.cliagent.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.FileInputStream
import java.util.Properties

/**
 * Day 17 — собственный MCP-сервер поверх GitHub REST API (read-only). Day 18 — remote-режим.
 *
 * Регистрирует один tool `get_repo(owner, repo)`: ходит в `GET /repos/{owner}/{repo}` и возвращает
 * читаемую сводку (full_name, stars, language, default_branch, open_issues, description).
 *
 * **Два режима транспорта** — выбирается env `CLI_AGENT_MCP_MODE` (`http` | `stdio`, default `stdio`):
 *  - `stdio` (default): JSON-RPC по stdin/stdout, локальный subprocess CLI-агента (день 17, без изменений).
 *  - `http`: remote Streamable HTTP на `embeddedServer(CIO)` (день 18 — деплой на VPS). Эндпоинт `/mcp`
 *    (SSE+POST+DELETE) поверх `Application.mcpStreamableHttp`, bearer-auth из `CLI_AGENT_MCP_TOKEN`.
 *
 * **Auth (http-режим):**
 *  - `CLI_AGENT_MCP_TOKEN` — bearer, проверяется application-interceptor'ом на КАЖДОМ запросе к `/mcp`
 *    ДО роутинга (нет/неверен → 401, MCP-обработка не запускается).
 *  - `CLI_AGENT_GITHUB_TOKEN` — как и в stdio, upstream PAT для GitHub API. В http-режиме держится
 *    ТОЛЬКО на сервере; клиенту не нужен — это улучшение безопасности remote-варианта.
 *
 * **Безопасность (по саммари Week 04):** read-only, least-privilege. owner/repo валидируются
 * allowlist-регексом (`[A-Za-z0-9._-]+`) перед подстановкой в URL — защита от path-injection.
 *
 * **stdout в stdio-режиме** несёт ТОЛЬКО JSON-RPC: никаких `println`/логов (ломают протокол). Логи SDK
 * (slf4j-simple) идут в stderr. В http-режиме stdout не критичен, но suppression оставлен — не мешает.
 *
 * **Lifecycle:**
 *  - stdio: `server.createSession(transport)` + `awaitCancellation()` (клиент убивает subprocess).
 *  - http: `embeddedServer.start(wait = true)`; extension `mcpStreamableHttp` создаёт НОВЫЙ `Server`
 *    на каждое соединение (через `block = { buildServer(...) }`), поэтому server-фабрика — отдельная
 *    функция, а не синглтон. (Server в 0.13.0 не наследует Protocol и не имеет `connect`.)
 */
fun main() {
    runBlocking {
        // Подавляем стартовое сообщение kotlin-logging («initializing... active logger factory»):
        // в stdio-режиме оно пишется в stdout (через println) и ломает JSON-RPC-канал MCP.
        // Флаг читается в init-блоке KotlinLogging при первом создании logger'а (внутри Server).
        KotlinLoggingConfiguration.logStartupMessage = false
        // slf4j-simple: системные свойства надёжнее classpath-файла simplelog.properties (тот
        // иногда не подхватывается при installDist). INFO-логи SDK идут в stderr — это не ломает
        // протокол, но мешает при ручной отладке.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        System.setProperty("org.slf4j.simpleLogger.log.io.modelcontextprotocol", "warn")

        val localProps = loadLocalProperties()

        val githubToken = System.getenv("CLI_AGENT_GITHUB_TOKEN") ?: localProps.getProperty("github.token")
        if (githubToken.isNullOrBlank()) {
            // В stderr — не ломаем stdout JSON-RPC (stdio-режим). Клиент увидит tool-error при вызове.
            System.err.println("[github-mcp] CLI_AGENT_GITHUB_TOKEN is not set — get_repo will return an error.")
        }

        val mode = System.getenv("CLI_AGENT_MCP_MODE")?.trim()?.lowercase() ?: "stdio"
        when (mode) {
            "http", "streamable", "streamable-http" -> runHttp(githubToken)
            else -> runStdio(githubToken)
        }
    }
}

/**
 * stdio-режим (день 17, без изменений поведения): JSON-RPC по stdin/stdout, сервер — subprocess.
 */
private suspend fun runStdio(githubToken: String?) {
    val server = buildServer(githubToken)
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    server.createSession(transport)
    awaitCancellation()
}

/**
 * http-режим (день 18): remote Streamable HTTP на embeddedServer(CIO).
 *
 * Эндпоинт `/mcp` (SSE + POST + DELETE) регистрирует extension `mcpStreamableHttp`, который сам ставит
 * ContentNegotiation + SSE plugin. `block` вызывается на НОВОЕ соединение → фабрика `buildServer`
 * строит свежий `Server` (с tool-регистрацией) каждый раз.
 *
 * Bearer-auth — application-interceptor в фазе Plugins (РАНЬШЕ роутинга): проверяет `Authorization`
 * на путях `[path]`, при несовпадении отдаёт 401 и `finish()` прерывает pipeline — MCP-обработка
 * не запускается. Так auth не зависит от внутренней логики extension'а.
 *
 * `allowedHosts = null` отключает DNS-rebinding-проверку SDK (по умолчанию пускает только
 * localhost/127.0.0.1/::1 — на VPS это отшьёт домен/IP; доступ ограничиваем bearer-auth + firewall).
 */
@Suppress("ExtractKtorModule") // один module, конфигурируем через lambda — компактнее для CLI-тула
private fun runHttp(githubToken: String?) {
    val host = System.getenv("CLI_AGENT_MCP_HOST")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0.0"
    val port = System.getenv("CLI_AGENT_MCP_PORT")?.trim()?.toIntOrNull() ?: 8080
    val path = System.getenv("CLI_AGENT_MCP_PATH")?.trim()?.takeIf { it.isNotEmpty() } ?: "/mcp"
    val expectedToken = System.getenv("CLI_AGENT_MCP_TOKEN")?.takeIf { it.isNotEmpty() }

    if (expectedToken == null) {
        // Remote-сервер без auth = открытый прокси к GitHub API под нашим токеном. Предупреждаем,
        // но НЕ отказываем запуск (dev/за-firewall-сценарии); для прод-деплоя токен обязателен.
        System.err.println(
            "[github-mcp] WARNING: CLI_AGENT_MCP_TOKEN is not set — /mcp is UNAUTHENTICATED. " +
                "Set it for any non-localhost deployment."
        )
    }

    embeddedServer(ServerCIO, port = port, host = host) {
        // Bearer-auth ДО роутинга: фаза Plugins отрабатывает раньше Routing, finish() прерывает
        // обработку → 401 отдаётся без захода в MCP-extension. `/mcp` — единственный эндпоинт
        // сервера, поэтому auth применяется ко всем запросам без фильтра по пути.
        if (expectedToken != null) {
            intercept(ApplicationCallPipeline.Plugins) {
                val auth = call.request.headers[HttpHeaders.Authorization]
                val ok = auth != null && auth.trim().removePrefix("Bearer").trim() == expectedToken
                if (!ok) {
                    call.respond(HttpStatusCode.Unauthorized)
                    finish()
                }
            }
        }

        mcpStreamableHttp(
            path = path,
            // DNS-rebinding protection SDK по умолчанию пускает только localhost/127.0.0.1/::1 —
            // на VPS запросы идут по реальному домену (Host: mcp.sukhoi27.ru) и отшиваются JSON-RPC
            // ошибкой "Invalid Host". `allowedHosts = null` НЕ отключает проверку, а даёт дефолт;
            // единственный способ пропустить произвольный домен — enableDnsRebindingProtection=false.
            // Доступ всё равно ограничен: bearer-auth (interceptor выше) + ufw + nginx TLS-terminator.
            enableDnsRebindingProtection = false,
        ) {
            // Фабрика свежего Server для этого соединения (extension создаёт session под него).
            buildServer(githubToken)
        }
    }.start(wait = true)
}

/**
 * Фабрика MCP-сервера: создаёт Server, регистрирует tool `get_repo`. Используется ОБОИМИ режимами
 * (stdio — один Server; http — новый Server на соединение, см. `runHttp`/`mcpStreamableHttp`).
 * HttpClient — per-Server (CIO, connection-pool); в http-режиме по одному пулу на сессию — приемлемо.
 */
private fun buildServer(githubToken: String?): Server {
    val server = Server(
        serverInfo = Implementation(name = "github", version = "0.1.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
        ),
    )

    val httpClient = HttpClient(ClientCIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }

    server.addTool(
        name = "get_repo",
        description = "Read-only GitHub repository metadata by owner/repo: full name, description, " +
            "stargazers count, primary language, default branch, open issues count, html url.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("owner") {
                    put("type", "string")
                    put("description", "Repository owner login, e.g. 'JetBrains'")
                }
                putJsonObject("repo") {
                    put("type", "string")
                    put("description", "Repository name, e.g. 'kotlin'")
                }
            },
            required = listOf("owner", "repo"),
        ),
    ) { req: CallToolRequest ->
        handleGetRepo(req, httpClient, githubToken)
    }

    return server
}

/** Допустимые символы для owner/repo в path-сегменте GitHub URL. */
private val NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")

private suspend fun handleGetRepo(
    req: CallToolRequest,
    httpClient: HttpClient,
    token: String?,
): CallToolResult {
    val args = req.arguments
    val owner = stringArg(args, "owner")?.trim()
    val repo = stringArg(args, "repo")?.trim()

    if (owner.isNullOrBlank() || repo.isNullOrBlank()) {
        return toolError("Both 'owner' and 'repo' are required (got owner=$owner, repo=$repo).")
    }
    if (!NAME_REGEX.matches(owner) || !NAME_REGEX.matches(repo)) {
        return toolError("Invalid owner/repo: only [A-Za-z0-9._-] allowed (path-injection guard).")
    }
    if (token.isNullOrBlank()) {
        return toolError("GitHub token (env CLI_AGENT_GITHUB_TOKEN) is not configured on the server.")
    }

    val url = "https://api.github.com/repos/$owner/$repo"
    val resp = try {
        httpClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header(HttpHeaders.UserAgent, "cli-agent-mcp")
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        return toolError("HTTP request failed: ${e.message}")
    }

    if (resp.status.value != 200) {
        val body = runCatching { resp.bodyAsText() }.getOrDefault("").take(300)
        return toolError("GitHub API returned HTTP ${resp.status.value}: $body")
    }

    val repoData = runCatching { resp.body<GitHubRepo>() }.getOrNull()
        ?: return toolError("Failed to parse GitHub response.")

    val summary = buildString {
        appendLine("Repository: ${repoData.fullName.ifBlank { "$owner/$repo" }}")
        repoData.htmlUrl?.let { appendLine("URL: $it") }
        appendLine("Stars: ${repoData.stars} ⭐")
        repoData.language?.let { appendLine("Language: $it") }
        repoData.defaultBranch?.let { appendLine("Default branch: $it") }
        appendLine("Open issues: ${repoData.openIssues}")
        repoData.description?.let { appendLine("Description: $it") }
    }.trimEnd()

    return CallToolResult(content = listOf(TextContent(summary)), isError = false)
}

private fun loadLocalProperties(): Properties {
    val props = Properties()
    val file = java.io.File("local.properties")
    if (file.exists()) {
        FileInputStream(file).use { props.load(it) }
    }
    return props
}

/** Tool-level error (isError=true) — по стандарту MCP не бросается как exception, а видна LLM. */
private fun toolError(message: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(message)), isError = true)

/** Извлекает строковый аргумент из JsonObject arguments; null если отсутствует/не строка. */
private fun stringArg(args: JsonObject?, key: String): String? {
    val el = args?.get(key) ?: return null
    val prim = el as? JsonPrimitive ?: return null
    return if (prim.isString) prim.content else null
}

@Serializable
private data class GitHubRepo(
    @SerialName("full_name") val fullName: String = "",
    val description: String? = null,
    @SerialName("stargazers_count") val stars: Int = 0,
    val language: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("open_issues_count") val openIssues: Int = 0,
    @SerialName("html_url") val htmlUrl: String? = null,
)
