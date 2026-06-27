# Задача R3. Убрать env-wiring, пустой scheduler + scope-проброс

## Цель

Адаптировать `McpServerApp.main()` под agent-driven модель: **убрать** `CLI_AGENT_WEATHER_CITIES`/
`_INTERVAL_SECONDS`, создавать **пустой** `WeatherScheduler` (без автоподписок), пробросить `scope` и
`scheduler` в `buildServer`. Scheduler наполняется агентом через tools (R2), а не env при старте.

> Переработка задачи 06. Обоснование — `R0-redesign-note.md`.

## Зависимости

R1 (`WeatherScheduler` v2 — конструктор `WeatherScheduler(client, store, scope, dispatcher)`),
R2 (`buildServer(..., scheduler)`). Текущая реализация задачи 06 (`McpServerApp.main`) — правится.

## Файл

`McpServerApp.kt` — правка `main()`, `runStdio`, `runHttp`.

## Что убрать / изменить

### `main()` — убрать weather-env, создать пустой scheduler
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

        // ── Day 18 (agent-driven redesign): погодные компоненты + пустой scheduler ──────
        // Регистрация periodic-сбора — ЧЕРЕЗ MCP-tools (subscribe_weather), а не env при старте.
        val weatherClient = WeatherClient()
        val weatherStore = WeatherStore()
        val schedulerScope = CoroutineScope(SupervisorJob())
        val scheduler = WeatherScheduler(weatherClient, weatherStore, schedulerScope)
        // пустой — никаких автоподписок; агент регистрирует через subscribe_weather.

        val mode = System.getenv("CLI_AGENT_MCP_MODE")?.trim()?.lowercase() ?: "stdio"
        when (mode) {
            "http", "streamable", "streamable-http" -> runHttp(githubToken, weatherClient, weatherStore, scheduler)
            else -> runStdio(githubToken, weatherClient, weatherStore, scheduler)
        }
    }
}
```
> **Удалить целиком** блок чтения `CLI_AGENT_WEATHER_INTERVAL_SECONDS`/`_CITIES` (из задачи 06).

### `runStdio` / `runHttp` — проброс scheduler в `buildServer`
```kotlin
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
    // stdio: subprocess короткоживущий, scheduler пуст → jobs стартуют только если агент
    // вызовет subscribe_weather. scope с SupervisorJob изолирован от MCP-сессии.
    awaitCancellation()
}
```
> В `runStdio` **убрать** `scheduler.start(...)` (этот метод больше не существует в R1 — scheduler
> пассивен до `subscribe`). Оставить `awaitCancellation()`.

```kotlin
private fun runHttp(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    scheduler: WeatherScheduler,
) {
    // ... host/port/path/token — БЕЗ ИЗМЕНЕНИЙ (задача 17-vps) ...
    // http-режим: scheduler хранит per-city jobs 24/7. Пустой при старте; агент наполняет через tools.
    // scope живёт всё время работы embeddedServer(wait=true).
    embeddedServer(ServerCIO, port = port, host = host) {
        // ... bearer-auth interceptor — БЕЗ ИЗМЕНЕНИЙ ...
        mcpStreamableHttp(path = path, enableDnsRebindingProtection = false) {
            // Погодные компоненты + scheduler — shared singleton'ы на процесс (один реестр подписок
            // для всех http-сессий; агент любой сессии видит те же подписки).
            buildServer(githubToken, weatherClient, weatherStore, scheduler)
        }
    }.start(wait = true)
}
```
> В `runHttp` **убрать** `scheduler.start(...)` (R1 не имеет `start`). Shared `scheduler` (один на
> процесс) — все http-сессии видят общий реестр подписок.

### Shutdown (опционально, JVM-exit достаточно)
`schedulerScope` с daemon-jobs — JVM-exit уберёт jobs автоматически. Явный `scheduler.stopAll()` в
shutdown-hook избыточен для CLI-сервера; не добавляем (YAGNI). Если в будущем потребуется graceful
shutdown — добавить `Runtime.getRuntime().addShutdownHook`.

## Ключевые инварианты

- **Env `CLI_AGENT_WEATHER_*` полностью убран** — единственный путь конфигурации расписания = tools
  (решение пользователя). `grep -r CLI_AGENT_WEATHER src/` → 0 совпадений.
- **Scheduler пустой при старте** — никаких автоподписок. Наполняется только через `subscribe_weather`
  (вызов агента). Это контрастирует со старой задачей 06 (env-список при старте).
- **Shared scheduler на процесс** (http-режим) — все http-сессии делят ОДИН реестр подписок. Агент
  сессии A подписался → агент сессии B видит подписку в `list_weather_subscriptions`. Это корректно:
  periodic-сбор — глобальная серверная активность, не per-session.
- **`schedulerScope = CoroutineScope(SupervisorJob())`** — единый lifecycle для всех per-city jobs;
  SupervisorJob → ошибка одного job не валит другие (AGENTS.md). Не привязан к `runBlocking` (scheduler
  jobs живут независимо от main-coroutine).
- **`runStdio`/`runHttp` больше не зовут `scheduler.start`** — метода нет в R1; scheduler пассивен,
  работает через `subscribe()`.

## Решения

- **Scope создаётся в `main()`, передаётся в `WeatherScheduler`** (не внутри scheduler) — единый
  lifecycle, testability (тесты R5 подставляют свой scope/dispatcher). Scheduler не порождает scope.
- **Без shutdown-hook** — daemon-coroutines + JVM-exit достаточно для CLI-сервера. Добавлять
  `stopAll()`-on-exit — over-engineering для задачи (24/7 на VPS: systemd restart убивает процесс,
  jobs уходят с ним; подписки в памяти и так теряются — по решению пользователя).
- **`XDG_DATA_HOME` остаётся** (для `WeatherStore`) — это не weather-config, а storage-путь; не убираем.

## Критерии готовности

- `./gradlew :mcp-server:installDist` — green.
- Сервер стартует **без** weather-env (`CLI_AGENT_WEATHER_*` не заданы) — никаких ошибок/предупреждений
  про погоду (в отличие от старой задачи 06).
- Raw `tools/list` → **7 tools** (подписки регистрируются агентом, не env).
- `grep -rn "CLI_AGENT_WEATHER" mcp-server/src/` → 0 совпадений.

## Зависимости (задачи)

Использует R1 (scheduler v2), R2 (`buildServer` сигнатура). Меняет задачу 06. Финальная сборка — R5.
