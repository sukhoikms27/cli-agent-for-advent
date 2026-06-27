package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.util.NAME_REGEX
import com.cliagent.mcp.server.util.stringArg
import com.cliagent.mcp.server.util.toolError
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Day 17 — GitHub tools (read-only). Перенесено из мономолита GitHubMcpServer.kt без изменения
 * поведения (pure refactor, задача 01). Логика `get_repo` идентична Day 17.
 *
 * Регистрирует один tool `get_repo(owner, repo)`: ходит в `GET /repos/{owner}/{repo}` и возвращает
 * читаемую сводку (full_name, stars, language, default_branch, open_issues, description).
 *
 * HttpClient — per-Server (CIO, connection-pool); в http-режиме по одному пулу на сессию — приемлемо.
 */
internal fun registerGitHubTools(server: Server, githubToken: String?) {
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
        handleGetRepo(req, httpClient, githubToken)
    }
}

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
        if (e is CancellationException) throw e
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
