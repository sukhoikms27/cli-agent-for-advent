package com.cliagent.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.FileInputStream
import java.util.Properties
import com.cliagent.mcp.server.weather.WeatherClient
import com.cliagent.mcp.server.weather.WeatherScheduler
import com.cliagent.mcp.server.weather.WeatherStore

/**
 * Точка входа MCP-сервера (Day 18 — вынесено из мономолита GitHubMcpServer.kt, pure refactor).
 *
 * Регистрация tool'ов — в [buildServer] (фабрика). Здесь — только lifecycle и transport.
 *
 * **Два режима транспорта** — выбирается env `CLI_AGENT_MCP_MODE` (`http` | `stdio`, default `stdio`):
 *  - `stdio` (default): JSON-RPC по stdin/stdout, локальный subprocess CLI-агента (день 17).
 *  - `http`: remote Streamable HTTP на `embeddedServer(CIO)` (день 17-vps — деплой на VPS).
 *    Эндпоинт `/mcp` (SSE+POST+DELETE) поверх `Application.mcpStreamableHttp`, bearer-auth из
 *    `CLI_AGENT_MCP_TOKEN`.
 *
 * **Auth (http-режим):**
 *  - `CLI_AGENT_MCP_TOKEN` — bearer, проверяется application-interceptor'ом на КАЖДОМ запросе к `/mcp`
 *    ДО роутинга (нет/неверен → 401, MCP-обработка не запускается).
 *  - `CLI_AGENT_GITHUB_TOKEN` — upstream PAT для GitHub API. В http-режиме держится ТОЛЬКО на
 *    сервере; клиенту не нужен (security-улучшение remote-варианта).
 *
 * **stdout в stdio-режиме** несёт ТОЛЬКО JSON-RPC: никаких `println`/логов (ломают протокол). Логи SDK
 * (slf4j-simple) идут в stderr. В http-режиме stdout не критичен, но suppression оставлен — не мешает.
 *
 * **Lifecycle:**
 *  - stdio: `server.createSession(transport)` + `awaitCancellation()` (клиент убивает subprocess).
 *  - http: `embeddedServer.start(wait = true)`; extension `mcpStreamableHttp` создаёт НОВЫЙ `Server`
 *    на каждое соединение (через `block = { buildServer(...) }`), поэтому server-фабрика — отдельная
 *    функция, а не синглтон. (Server в SDK 0.13.0 не наследует Protocol и не имеет `connect`.)
 */
fun main() {
    runBlocking {
        // Подавляем стартовое сообщение kotlin-logging («initializing... active logger factory»):
        // в stdio-режиме оно пишется в stdout (через println) и ломает JSON-RPC-канал MCP.
        KotlinLoggingConfiguration.logStartupMessage = false
        // slf4j-simple: системные свойства надёжнее classpath-файла simplelog.properties (тот
        // иногда не подхватывается при installDist). INFO-логи SDK идут в stderr — не ломают
        // протокол, но мешают при ручной отладке.
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
        System.setProperty("org.slf4j.simpleLogger.log.io.modelcontextprotocol", "warn")

        val localProps = loadLocalProperties()

        val githubToken = System.getenv("CLI_AGENT_GITHUB_TOKEN") ?: localProps.getProperty("github.token")
        if (githubToken.isNullOrBlank()) {
            // В stderr — не ломаем stdout JSON-RPC (stdio-режим). Клиент увидит tool-error при вызове.
            System.err.println("[mcp] CLI_AGENT_GITHUB_TOKEN is not set — get_repo will return an error.")
        }

        // ── Day 18 (agent-driven redesign R1/R3): погодные компоненты + ПУСТОЙ scheduler ────
        // Регистрация periodic-сбора — ЧЕРЕЗ MCP-tools (subscribe_weather), а не env при старте.
        // Singleton'ы на процесс — shared между всеми Server'ами http-сессий. Scheduler наполняется
        // агентом; scope с SupervisorJob хранит per-city jobs, изолированные от MCP-сессии.
        val weatherClient = WeatherClient()
        val weatherStore = WeatherStore()
        val schedulerScope = CoroutineScope(SupervisorJob())
        val scheduler = WeatherScheduler(weatherClient, weatherStore, schedulerScope)

        val mode = System.getenv("CLI_AGENT_MCP_MODE")?.trim()?.lowercase() ?: "stdio"
        when (mode) {
            "http", "streamable", "streamable-http" -> runHttp(githubToken, weatherClient, weatherStore, scheduler)
            else -> runStdio(githubToken, weatherClient, weatherStore, scheduler)
        }
    }
}

/**
 * stdio-режим (день 17, без изменений поведения): JSON-RPC по stdin/stdout, сервер — subprocess.
 */
private suspend fun runStdio(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    scheduler: WeatherScheduler,
) {
    val server = buildServer(githubToken, weatherClient, weatherStore, scheduler)
    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )
    server.createSession(transport)
    // Day 18 (agent-driven): scheduler пуст при старте, наполняется агентом через subscribe_weather.
    // per-city jobs живут в schedulerScope (SupervisorJob), изолированном от MCP-сессии.
    awaitCancellation()
}

/**
 * http-режим (день 17-vps): remote Streamable HTTP на embeddedServer(CIO).
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
        // Remote-сервер без auth = открытый прокси к GitHub API под нашим токеном. Предупреждаем,
        // но НЕ отказываем запуск (dev/за-firewall-сценарии); для прод-деплоя токен обязателен.
        System.err.println(
            "[mcp] WARNING: CLI_AGENT_MCP_TOKEN is not set — /mcp is UNAUTHENTICATED. " +
                "Set it for any non-localhost deployment."
        )
    }

    // Day 18 (agent-driven): http-режим — основной для «24/7 агента». Scheduler пуст при старте;
    // агент регистрирует per-city jobs через subscribe_weather. scope живёт всё время работы
    // embeddedServer(wait=true); shared scheduler — общий реестр подписок для всех http-сессий.

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
            // ошибкой "Invalid Host". `enableDnsRebindingProtection = false` пропускает произвольный
            // домен; доступ всё равно ограничен: bearer-auth (interceptor выше) + ufw + nginx TLS.
            enableDnsRebindingProtection = false,
        ) {
            // Фабрика свежего Server для этого соединения (extension создаёт session под него).
            // Погодные компоненты + scheduler — shared singleton'ы модуля (один реестр подписок на процесс).
            buildServer(githubToken, weatherClient, weatherStore, scheduler)
        }
    }.start(wait = true)
}

private fun loadLocalProperties(): Properties {
    val props = Properties()
    val file = java.io.File("local.properties")
    if (file.exists()) {
        FileInputStream(file).use { props.load(it) }
    }
    return props
}
