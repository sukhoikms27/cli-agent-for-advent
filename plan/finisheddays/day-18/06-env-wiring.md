# Задача 06. Wiring: env → `WeatherClient` / `WeatherStore` / `WeatherScheduler` → `buildServer`

> ⚠️ **СУПЕРСЕДЕНО задачей R3.** Этот файл описывает ИСХОДНЫЙ wiring (env `CLI_AGENT_WEATHER_*` →
> scheduler с автоподписками). Фактическая реализация — **agent-driven**: scheduler пуст при старте,
> наполняется агентом через `subscribe_weather`. Env-переменные убраны полностью. См.
> [`R3-remove-env-wiring.md`](./R3-remove-env-wiring.md). Обоснование —
> [`R0-redesign-note.md`](./R0-redesign-note.md). Файл оставлен как историческая справка.

## Цель

Собрать всё вместе в `main()`: создать погодные компоненты из env-переменных, прокинуть в
`McpServerFactory.buildServer(...)` (расширенная сигнатура из задачи 04), запустить scheduler.
**Клиентский модуль `src/main/...` НЕ трогается** — tools авто-подхватываются (Day 17 wiring).

## Зависимости

01 (`McpServerApp.main`, `McpServerFactory.buildServer` сигнатура), 02 (`WeatherStore`), 03
(`WeatherClient`), 04 (`registerWeatherTools` + сигнатура `buildServer(githubToken, client, store)`),
05 (`WeatherScheduler`). Образец env-чтения — `McpServerApp.main` (Day 17-vps: `CLI_AGENT_MCP_MODE`
etc.).

## Файлы (правки)

| Файл | Изменение |
|---|---|
| `McpServerApp.kt` | В `main()`: чтение weather env, создание client/store/scheduler, старт scheduler, прокидывание в `runStdio`/`runHttp` |
| `McpServerFactory.kt` | Сигнатура `buildServer(githubToken, weatherClient, weatherStore)` (из задачи 04) — здесь лишь принимающая сторона |

## Что реализовать

### Правка `McpServerApp.main()` (добавить блок после чтения githubToken)

```kotlin
fun main() {
    runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        System.setProperty("org.slf4j.simpleLogger.log.io.modelcontextprotocol", "warn")

        val localProps = loadLocalProperties()

        val githubToken = System.getenv("CLI_AGENT_GITHUB_TOKEN") ?: localProps.getProperty("github.token")
        if (githubToken.isNullOrBlank()) {
            System.err.println("[mcp] CLI_AGENT_GITHUB_TOKEN is not set — get_repo will return an error.")
        }

        // ── Day 18: погодные компоненты ──────────────────────────────────────────
        val weatherClient = WeatherClient()
        val weatherStore = WeatherStore()

        val intervalSeconds = (System.getenv("CLI_AGENT_WEATHER_INTERVAL_SECONDS") ?: "3600")
            .trim().toLongOrNull()?.takeIf { it > 0 } ?: 3600L
        val cities = (System.getenv("CLI_AGENT_WEATHER_CITIES") ?: "Moscow")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val scheduler = WeatherScheduler(weatherClient, weatherStore, cities, intervalSeconds)

        val mode = System.getenv("CLI_AGENT_MCP_MODE")?.trim()?.lowercase() ?: "stdio"
        when (mode) {
            "http", "streamable", "streamable-http" -> runHttp(githubToken, weatherClient, weatherStore, scheduler)
            else -> runStdio(githubToken, weatherClient, weatherStore, scheduler)
        }
    }
}
```

### Правка `runStdio` / `runHttp` — проброс компонентов + lifecycle scheduler

```kotlin
private suspend fun runStdio(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    scheduler: WeatherScheduler,
) {
    val server = buildServer(githubToken, weatherClient, weatherStore)
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    server.createSession(transport)
    // stdio: subprocess короткоживущий — scheduler стартует, но обычно не успевает собрать.
    // Запускаем всё же — корректность behavior не зависит от режима.
    scheduler.start(CoroutineScope(SupervisorJob()))
    awaitCancellation()
}

private fun runHttp(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    scheduler: WeatherScheduler,
) {
    val host = System.getenv("CLI_AGENT_MCP_HOST")?.trim()?.takeIf { it.isNotEmpty() } ?: "0.0.0.0"
    val port = System.getenv("CLI_AGENT_MCP_PORT")?.trim()?.toIntOrNull() ?: 8080
    val path = System.getenv("CLI_AGENT_MCP_PATH")?.trim()?.takeIf { it.isNotEmpty() } ?: "/mcp"
    val expectedToken = System.getenv("CLI_AGENT_MCP_TOKEN")?.takeIf { it.isNotEmpty() }

    if (expectedToken == null) {
        System.err.println("[mcp] WARNING: CLI_AGENT_MCP_TOKEN is not set — /mcp is UNAUTHENTICATED.")
    }

    // http-режим — основной для Day 18 (24/7 агент): scheduler стартует и копит данные.
    scheduler.start(CoroutineScope(SupervisorJob()))

    embeddedServer(ServerCIO, port = port, host = host) {
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
            enableDnsRebindingProtection = false,
        ) {
            // buildServer вызывается на НОВОЕ соединение — погодные компоненты shared (singleton'ы
            // модуля): один client/store/scheduler на процесс, Server'ы создаются под сессии.
            buildServer(githubToken, weatherClient, weatherStore)
        }
    }.start(wait = true)
}
```

## Ключевые инварианты

- **Клиентский модуль `src/main/...` НЕ меняется.** `McpTool.toToolDefinition()` (Day 17) маппит
  любые tools; `ContextAwareAgent.runToolLoop` поддерживает несколько tools + chaining. Новые
  погодные tools видны клиенту после рестарта сервера автоматически.
- **Singleton-компоненты на процесс**: `weatherClient`/`weatherStore`/`scheduler` создаются ОДИН
  раз в `main()`, прокидываются во все `buildServer(...)`-вызовы (http: per-соединение Server'ы
  делят общие storage/client). Так scheduler копит в общий store, который читают tools любой сессии.
- **Scheduler scope = `CoroutineScope(SupervisorJob())`** — изолирован от MCP-сессий; supervisor →
  ошибка одной корутины не валит остальные (AGENTS.md §REPL, применимо и к scheduler).
- **Env-defaults безопасные** — `intervalSeconds=3600`, `cities=[Moscow]`; невалидное значение →
  default. Empty cities → scheduler disabled (см. задачу 05).
- **`WeatherClient` не закрывается явно** — живёт вечно (process lifetime); его `close()` не нужен,
  JVM-exit освободит ресурсы. (Альтернатива: shutdown-hook, но для daemon-CIO-пула избыточно.)
- **Bearer-auth логика не трогается** (Day 17-vps) — только прокидывание погодных компонентов.

## Решения

- **Scheduler стартует в обоих режимах** — корректность behavior не зависит от транспорта. В stdio
  он инертен (subprocess короткоживущий), в http — активен (24/7). Не плодим if-branches по режимам.
- **Компоненты создаются в `main()`, не в `buildServer`** — shared state между сессиями http;
  `buildServer` остаётся pure-фабрикой Server'ов (только регистрация tools).
- **`CLI_AGENT_WEATHER_*` env, не config.json** — mcp-server не имеет config-файла (это application
  на VPS), env удобнее для systemd-unit (`Environment=`). Согласовано с Day 17 pattern (`CLI_AGENT_MCP_*`).

## Критерии готовности

- `./gradlew :mcp-server:shadowJar` — green (fat-jar собирается).
- `java -jar mcp-server/build/libs/mcp-server-0.1.0-all.jar` со средой `CLI_AGENT_MCP_MODE=http
  CLI_AGENT_MCP_PORT=8080 CLI_AGENT_MCP_TOKEN=dev CLI_AGENT_WEATHER_INTERVAL_SECONDS=60
  CLI_AGENT_WEATHER_CITIES=Moscow` → в stderr `[weather] scheduler started...` затем каждые 60с
  `[weather] collected Moscow: ...`.
- Файл `$XDG_DATA_HOME/cli-agent/weather/moscow.json` создаётся и растёт.
- Raw JSON-RPC `tools/list` через bearer → 4 tools (get_repo + 3 погодных).
- Клиентский модуль `src/main/...` — 0 diff.

## Зависимости (задачи)

Использует 01 (main/factory), 02 (store), 03 (client), 04 (buildServer сигнатура), 05 (scheduler).
Финальная сборка батча 1; верифицируется в 12, демо в 13.
