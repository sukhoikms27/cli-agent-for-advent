# Задача R2. 3 новых MCP-tools подписок + расширенные сигнатуры

## Цель

Добавить MCP-tools, которыми **агент управляет расписанием**: `subscribe_weather`,
`list_weather_subscriptions`, `unsubscribe_weather`. Расширить сигнатуры `registerWeatherTools` и
`buildServer`, чтобы пробросить `WeatherScheduler`. Существующие 4 tools — без изменений. Итого **7 tools**.

> Обоснование — `R0-redesign-note.md`. Переработка задачи 04 (расширение).

## Зависимости

R1 (`WeatherScheduler` v2 с `subscribe`/`unsubscribe`/`list`). 04 (`registerWeatherTools`,
`util/Args`: `stringArg`/`numberArg`/`toolError`). Образец регистрации — `collect_weather` (задача 04).

## Файлы

| Файл | Изменение |
|---|---|
| `tools/WeatherTools.kt` | +3 tool'а + расширить сигнатуру `registerWeatherTools(..., scheduler)` |
| `McpServerFactory.kt` | расширить сигнатуру `buildServer(..., scheduler)` |

## Что реализовать

### Правка `WeatherTools.kt` — расширить сигнатуру + 3 новых tool'а

```kotlin
// Сигнатура расширяется: + WeatherScheduler для управления подписками.
internal fun registerWeatherTools(
    server: Server,
    client: WeatherClient,
    store: WeatherStore,
    scheduler: WeatherScheduler,   // NEW (R2)
) {
    // ... существующие collect_weather / get_current_weather / get_weather_summary — БЕЗ ИЗМЕНЕНИЙ ...

    // 4) Подписка на периодический сбор (agent-driven «по расписанию», Day 18 redesign).
    server.addTool(
        name = "subscribe_weather",
        description = "Подписаться на периодический сбор погоды по городу: сервер будет сам собирать " +
            "и сохранять данные каждые N минут (24/7, в фоне). Используй для непрерывного мониторинга. " +
            "Первый замер — через interval_minutes минут после подписки. При повторной подписке на тот " +
            "же город интервал обновляется.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "Название города, например 'Moscow'")
                }
                putJsonObject("interval_minutes") {
                    put("type", "integer")
                    put("description", "Интервал сбора в минутах (1..10080). По умолчанию 60 (раз в час)")
                    put("default", 60)
                }
            },
            required = listOf("city"),
        ),
    ) { req -> handleSubscribeWeather(req, scheduler) }

    // 5) Список активных подписок.
    server.addTool(
        name = "list_weather_subscriptions",
        description = "Показать активные подписки на периодический сбор погоды: город, интервал, статус. " +
            "Помогает проверить, какие города собираются автоматически.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) { _ -> handleListWeatherSubscriptions(scheduler) }

    // 6) Отписка от периодического сбора.
    server.addTool(
        name = "unsubscribe_weather",
        description = "Остановить периодический сбор погоды по городу. После этого сервер перестаёт " +
            "собирать данные; уже накопленные снапшоты сохраняются и доступны через get_weather_summary.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") { put("type", "string"); put("description", "Название города") }
            },
            required = listOf("city"),
        ),
    ) { req -> handleUnsubscribeWeather(req, scheduler) }
}

// ── handlers подписок ────────────────────────────────────────────────────────

private fun handleSubscribeWeather(
    req: CallToolRequest, scheduler: WeatherScheduler,
): CallToolResult {
    val city = stringArg(req.arguments, "city")?.trim()
    if (city.isNullOrBlank()) return toolError("Параметр 'city' обязателен.")
    val interval = (numberArg(req.arguments, "interval_minutes")?.toLong()) ?: 60L
    val result = scheduler.subscribe(city, interval)   // coerce внутри (R1)
    val text = "✓ Подписка активна: ${result.city}, сбор раз в ${result.intervalMinutes} мин. " +
        "Первый замер — через ${result.intervalMinutes} мин. Используйте get_weather_summary для сводки."
    return CallToolResult(content = listOf(TextContent(text)), isError = false)
}

private fun handleListWeatherSubscriptions(scheduler: WeatherScheduler): CallToolResult {
    val subs = scheduler.list()
    if (subs.isEmpty()) {
        return CallToolResult(
            content = listOf(TextContent("Активных подписок нет. Используйте subscribe_weather для запуска сбора.")),
            isError = false,
        )
    }
    val text = buildString {
        appendLine("Активные подписки (${subs.size}):")
        subs.forEach { appendLine("  • ${it.city}: раз в ${it.intervalMinutes} мин — ${if (it.active) "активна" else "остановлена"}") }
    }.trimEnd()
    return CallToolResult(content = listOf(TextContent(text)), isError = false)
}

private fun handleUnsubscribeWeather(
    req: CallToolRequest, scheduler: WeatherScheduler,
): CallToolResult {
    val city = stringArg(req.arguments, "city")?.trim()
    if (city.isNullOrBlank()) return toolError("Параметр 'city' обязателен.")
    val removed = scheduler.unsubscribe(city)
    val text = if (removed) {
        "✓ Подписка на '$city' остановлена. Накопленные данные сохранены."
    } else {
        "Подписки на '$city' не было."
    }
    return CallToolResult(content = listOf(TextContent(text)), isError = false)
}
```

### Правка `McpServerFactory.kt` — проброс scheduler

```kotlin
internal fun buildServer(
    githubToken: String?,
    weatherClient: WeatherClient,
    weatherStore: WeatherStore,
    weatherScheduler: WeatherScheduler,   // NEW (R2)
): Server {
    val server = Server(
        serverInfo = Implementation(name = "cli-agent-mcp", version = "0.1.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
    )
    registerGitHubTools(server, githubToken)
    registerWeatherTools(server, weatherClient, weatherStore, weatherScheduler)
    return server
}
```

## Ключевые инварианты

- **Существующие 4 tools не трогаются** — `collect_weather`/`get_current_weather`/`get_weather_summary`
  работают как прежде. `collect_weather` — разовый замер, `subscribe_weather` — периодический;
  различие отражено в описаниях (LLM сама выбирает по контексту запроса).
- **`interval_minutes` default 60** — разумный для прод-сценария (раз в час); для демо агент может
  задать 1. Coerce в [1,10080] внутри `WeatherScheduler.subscribe` (R1).
- **Tool-ошибки → `toolError`** (`isError=true`) — пустой `city`. Протокольные ошибки — throw.
- **`list_weather_subscriptions` без required-аргументов** — `properties = {}`, `required = []`.
  LLM зовёт без аргументов.
- **Ответы человекочитаемы (RU)** — демо/профиль русскоязычный; `✓`/`•` для визуальной ясности в REPL.
- **`unsubscribe_weather` НЕ удаляет накопленные данные** — только останавливает сбор; снапшоты
  доступны через `get_weather_summary` (явно сказано в описании + ответе).

## Решения

- **`interval_minutes` в минутах, не секундах** — минута минимальная гранулярность разумна для погоды
  (чаще — спам API); «раз в час» = 60. Coerce защищает от 0/абсурда.
- **Подписка ≠ collect** — два разных tool'а для двух семантик: subscribe (24/7 periodic), collect
  (разовый по запросу). LLM видит оба; описание каждого явно объясняет назначение.
- **`list_weather_subscriptions` показывает `active`** — `job.isActive` (true если job крутится). После
  `unsubscribe` подписки в списке нет (она удалена из реестра).

## Критерии готовности

- `./gradlew :mcp-server:build` — green.
- Raw JSON-RPC `tools/list` → ровно **7 tools**: `get_repo`, `collect_weather`, `get_current_weather`,
  `get_weather_summary`, `subscribe_weather`, `list_weather_subscriptions`, `unsubscribe_weather`.
- Raw `tools/call subscribe_weather {city, interval_minutes}` → `isError:false`, «Подписка активна».
- Raw `list_weather_subscriptions` → подписка видна. `unsubscribe_weather` → удалена.

## Зависимости (задачи)

Использует R1 (scheduler). Сигнатура `buildServer` принимается в R3 (wiring). Не трогает 04 (tools) логику.
