package com.cliagent.mcp.server

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
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
 * Day 17 — собственный MCP-сервер (stdio) поверх GitHub REST API (read-only).
 *
 * Регистрирует один tool `get_repo(owner, repo)`: ходит в `GET /repos/{owner}/{repo}` и возвращает
 * читаемую сводку (full_name, stars, language, default_branch, open_issues, description).
 *
 * **Transport:** stdio — JSON-RPC идёт по stdin/stdout. Поэтому stdout несёт ТОЛЬКО JSON-RPC:
 * никаких `println`/логов в stdout (они сломают протокол). Логи SDK (slf4j-simple) идут в stderr.
 *
 * **Auth:** Personal Access Token из env `CLI_AGENT_GITHUB_TOKEN`. Сервер — subprocess, запускаемый
 * `McpClient` через `ProcessBuilder`, который наследует env родителя → токен попадает сюда без правок
 * клиента. Токен **не логируется**.
 *
 * **Безопасность (по саммари Week 04):** read-only, least-privilege. owner/repo валидируются
 * allowlist-регексом (`[A-Za-z0-9._-]+`) перед подстановкой в URL — защита от path-injection.
 *
 * **Lifecycle:** `server.createSession(transport)` регистрирует handlers (tools/list, tools/call),
 * коннектит session к транспорту (запускает reader/writer/processor) и возвращается;
 * `awaitCancellation()` держит процесс живым, пока клиент (`McpClient.close` → `stopProcess`) его не
 * убьёт — тот же паттерн, что у reference npx-сервера дня 16. (Server в 0.13.0 не наследует Protocol
 * и не имеет `connect`; используется `createSession`.)
 */
fun main() {
    runBlocking {
        // Подавляем стартовое сообщение kotlin-logging («initializing... active logger factory»),
        // иначе оно пишется в stdout (через println) и ломает JSON-RPC-канал MCP (stdio).
        // Флаг читается в init-блоке KotlinLogging при первом создании logger'а (внутри Server).
        KotlinLoggingConfiguration.logStartupMessage = false
        // slf4j-simple: системные свойства надёжнее classpath-файла simplelog.properties (тот
        // иногда не подхватывается при installDist). INFO-логи SDK идут в stderr — это не ломает
        // протокол, но мешает при ручной отладке.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        System.setProperty("org.slf4j.simpleLogger.log.io.modelcontextprotocol", "warn")

        val localProps = loadLocalProperties()

        val token = System.getenv("CLI_AGENT_GITHUB_TOKEN") ?: localProps.getProperty("github.token")
        if (token.isNullOrBlank()) {
            // В stderr — не ломаем stdout JSON-RPC. Клиент увидит tool-error при вызове.
            System.err.println("[github-mcp] CLI_AGENT_GITHUB_TOKEN is not set — get_repo will return an error.")
        }

        val server = Server(
            serverInfo = Implementation(name = "github", version = "0.1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools()),
            ),
        )

        val httpClient = HttpClient(CIO) {
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
            handleGetRepo(req, httpClient, token)
        }

        val transport = StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered(),
        )
        server.createSession(transport)
        awaitCancellation()
    }
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
