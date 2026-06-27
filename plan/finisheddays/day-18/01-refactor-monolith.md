# Задача 01. Рефактор мономолита `GitHubMcpServer.kt` → чистая расширяемая архитектура

## Цель

Разбить мономолитный `GitHubMcpServer.kt` (303 строки, всё в одном файле: `main()`, оба транспорта,
`buildServer()`, GitHub-handler, helpers, DTO) по пакетам. Подготовить **точку расширения** —
`McpServerFactory`, куда день 18 добавит погодные tools одной строкой. **Поведение Day 17 (`get_repo`)
не меняется** — это pure refactor.

## Зависимости

Корневая задача дня (фундамент для 02–06). Образец transport-логики — текущий
`mcp-server/src/main/kotlin/com/cliagent/mcp/server/GitHubMcpServer.kt`.

## Что сделать (перенос по файлам)

### Новый `McpServerApp.kt` (главный файл, main class)
Перенести из `GitHubMcpServer.kt` **без изменения поведения**:
- `main()` (подавление kotlin-logging stdout, чтение env/`local.properties`, ветвление `CLI_AGENT_MCP_MODE`).
- `runStdio(githubToken)` — stdio-транспорт.
- `runHttp(githubToken)` — http-транспорт + bearer-interceptor.
- `loadLocalProperties()`.

В `main()` временно оставить создание только `githubToken` (погодные client/store/scheduler добавит
задача 06). `runStdio`/`runHttp` вызывают `McpServerFactory.buildServer(githubToken)`.

> **Имя main class** меняется: `com.cliagent.mcp.server.GitHubMcpServerKt` →
> `com.cliagent.mcp.server.McpServerAppKt` → правка `application { mainClass.set(...) }` в
> `mcp-server/build.gradle.kts`. **serverInfo** name `"github"` → `"cli-agent-mcp"` (нейтральный,
> мульти-tool).

### Новый `McpServerFactory.kt` (точка расширения)
Вынести `buildServer(...)`:
```kotlin
package com.cliagent.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Фабрика MCP-сервера: создаёт Server и регистрирует ВСЕ tools. Точка расширения дня 18:
 * добавление tool = регистрация здесь + (опц.) новые зависимости в сигнатуре.
 *
 * Используется ОБОИМИ режимами: stdio — один Server; http — новый Server на соединение
 * (см. McpServerApp.runHttp → mcpStreamableHttp → block = { buildServer(...) }).
 *
 * @param githubToken PAT для get_repo (Day 17), null → tool-error при вызове
 */
internal fun buildServer(githubToken: String?): Server {
    val server = Server(
        serverInfo = Implementation(name = "cli-agent-mcp", version = "0.1.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
    )
    registerGitHubTools(server, githubToken)        // Day 17 — без изменений
    // Day 18 (задача 04): registerWeatherTools(server, weatherClient, weatherStore)
    return server
}
```
> Сигнатура `buildServer` расширится в задаче 06 (`buildServer(githubToken, weatherClient, weatherStore)`).
> В этой задаче — только githubToken, чтобы `get_repo` работал как прежде.

### Новый `util/Args.kt` (общие helpers)
Перенести из мономолита:
- `toolError(message: String): CallToolResult` — tool-level error (`isError=true`).
- `stringArg(args: JsonObject?, key: String): String?` — извлечение строкового аргумента.
- `numberArg(args: JsonObject?, key: String): Double?` (новый — нужен погодным tools в 04) —
  извлечение числового аргумента с coercion (int/double).
- `val NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")` — allowlist для GitHub owner/repo.

```kotlin
package com.cliagent.mcp.server.util

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

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
```

### Новый `tools/GitHubTools.kt` (get_repo — вынесен, без изменений)
Перенести `handleGetRepo` + `GitHubRepo` DTO + функцию-регистратор:
```kotlin
package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.util.NAME_REGEX
import com.cliagent.mcp.server.util.numberArg // нет; для get_repo не нужен
import com.cliagent.mcp.server.util.stringArg
import com.cliagent.mcp.server.util.toolError
// ... (imports MCP SDK + Ktor + serialization — как в оригинале)

/** Регистрирует Day 17 tools: get_repo (read-only GitHub metadata). Поведение идентично Day 17. */
internal fun registerGitHubTools(server: Server, githubToken: String?) {
    server.addTool(
        name = "get_repo",
        description = "Read-only GitHub repository metadata by owner/repo: full name, description, " +
            "stargazers count, primary language, default branch, open issues count, html url.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("owner") { put("type", "string"); put("description", "Repository owner login, e.g. 'JetBrains'") }
                putJsonObject("repo") { put("type", "string"); put("description", "Repository name, e.g. 'kotlin'") }
            },
            required = listOf("owner", "repo"),
        ),
    ) { req -> handleGetRepo(req, httpClient, githubToken) }
    // httpClient создаётся в registerGitHubTools (per-Server CIO), как раньше в buildServer.
}
```
> Логика `handleGetRepo` (validation `NAME_REGEX`, GET `/repos/{owner}/{repo}`, parse `GitHubRepo`,
> форматирование сводки) переносится **дословно**. HttpClient создаётся внутри `registerGitHubTools`
> (per-Server, как в оригинальном `buildServer`).

### Удалить `GitHubMcpServer.kt`
После переноса всего содержимого — старый файл удалить. (Если проект запрещает git-операции — файл
просто перезаписывается/удаляется в рабочей копии, без коммита.)

## Сводка перемещений

| Было в `GitHubMcpServer.kt` | Стало |
|---|---|
| `main()`, `runStdio`, `runHttp`, `loadLocalProperties` | `McpServerApp.kt` |
| `buildServer()` | `McpServerFactory.kt` |
| `NAME_REGEX`, `toolError`, `stringArg` (+ новый `numberArg`) | `util/Args.kt` |
| `handleGetRepo`, `GitHubRepo` (+ регистрация) | `tools/GitHubTools.kt` |

## Ключевые инварианты

- **0 регрессий Day 17:** `get_repo` возвращает идентичный результат; validation/headers/error-paths
  те же. Это pure structural refactor.
- **serverInfo name** `"github"` → `"cli-agent-mcp"` — нейтральный под мульти-tool. На клиенте
  имя сервера не валидируется (только tools/list), изменения поведения нет.
- **Visibility `internal`:** все компоненты модуля — `internal` (публичного API у mcp-server нет,
  это application-модуль).
- **HttpClient per-Server** сохраняется (CIO внутри `registerGitHubTools`, как раньше в buildServer)
  — пул соединений на сессию.
- **Точка расширения** — `McpServerFactory.buildServer`: задача 04 добавит `registerWeatherTools(...)`,
  задача 06 расширит сигнатуру. Не ломает существующую регистрацию.

## Решения

- **Не переименовывать модуль/gradle-task** (`:mcp-server`, `application { ... }`) — только main class.
  Меньше поверхности изменений, deployscript'ы VPS (systemd `ExecStart java -jar mcp-server-0.1.0-all.jar`)
  не зависят от имени main class.
- **`internal`-видимость** — mcp-server не библиотека, экспортировать ничего не нужно; `internal`
  ускоряет компиляцию и явно фиксирует границы.
- **Пакеты `util/`, `tools/`, `weather/`** — отражают слои: helpers / tool-регистраторы /
  домен погоды. День 19 (композиция tools) ровно ляжет в `tools/`.

## Критерии готовности

- `./gradlew :mcp-server:installDist build` — green.
- Raw JSON-RPC `tools/list` → **ровно 1 tool** `get_repo` с прежней schema (owner/repo).
- Raw `tools/call get_repo` без токена → `isError:true` (как в Day 17).
- Файл `GitHubMcpServer.kt` удалён; в пакете `com.cliagent.mcp.server` — `McpServerApp.kt`,
  `McpServerFactory.kt`, `util/Args.kt`, `tools/GitHubTools.kt`.
- `mcp-server/build.gradle.kts`: `mainClass.set("com.cliagent.mcp.server.McpServerAppKt")`.

## Зависимости (задачи)

Корень дня. Дальше: 02 (storage) и 03 (client) — независимо друг от друга.
