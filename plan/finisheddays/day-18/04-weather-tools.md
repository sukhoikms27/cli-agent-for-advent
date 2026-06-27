# Задача 04. Три погодных MCP-tools + регистрация в `McpServerFactory`

## Цель

Зарегистрировать 3 погодных tool'а — это и есть MCP-выражение задания Day 18 (по подсказке куратора
«у MCP есть понятие tool»):

1. **`collect_weather`** — on-demand сбор (геокод+forecast→store). Триггер «по расписанию», который
   LLM может вызывать сама.
2. **`get_current_weather`** — последний снапшот из store (или свежий fetch, если store пуст).
3. **`get_weather_summary`** — **агрегат** за период (avg/min/max температура, суммарные осадки,
   диапазон, число замеров). Закрывает «агрегированный результат» / «регулярный summary».

> **Клиентский модуль НЕ меняется.** Tools авто-подхватываются через `McpTool.toToolDefinition()`
> (Day 17) и существующий `ContextAwareAgent.runToolLoop` (поддерживает несколько tools + chaining,
> `MAX_TOOL_ROUNDS=4`). day17-results anticipated «Day 18 — 2+ tools, LLM выбирает/чейнит».

## Зависимости

02 (`WeatherStore`, `weatherDescription`), 03 (`WeatherClient`), 01 (`McpServerFactory` — точка
регистрации, `util/Args` — `stringArg`/`numberArg`/`toolError`). Образец регистрации —
`registerGitHubTools` (Day 17: `server.addTool(name, description, ToolSchema) { req -> handler }`).

## Файлы

| Файл | Содержание |
|---|---|
| `tools/WeatherTools.kt` (новый) | `registerWeatherTools(server, client, store)` + handlers + `aggregate()` |
| `McpServerFactory.kt` (правка) | вызов `registerWeatherTools(...)` + расширение сигнатуры `buildServer` |

## Что реализовать

### `tools/WeatherTools.kt`
```kotlin
package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.util.numberArg
import com.cliagent.mcp.server.util.stringArg
import com.cliagent.mcp.server.util.toolError
import com.cliagent.mcp.server.weather.WeatherClient
import com.cliagent.mcp.server.weather.WeatherSnapshot
import com.cliagent.mcp.server.weather.WeatherStore
import com.cliagent.mcp.server.weather.weatherDescription
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Регистрирует погодные tools дня 18. Все read/write через [WeatherClient]/[WeatherStore]; tool-level
 * ошибки → toolError (isError=true, видны LLM), не exception (конвенция MCP).
 *
 * `aggregate()` — чистая функция (без IO), вынесена для тестируемости (задача 11).
 */
internal fun registerWeatherTools(server: Server, client: WeatherClient, store: WeatherStore) {

    // 1) On-demand сбор: geocode + forecast → store.append. Возвращает текст снапшота.
    server.addTool(
        name = "collect_weather",
        description = "Собрать свежий замер погоды по названию города (Open-Meteo) и сохранить " +
            "в хранилище. Используй, чтобы накопить данные или инициировать периодический сбор. " +
            "Возвращает текущую температуру, ветер, осадки и описание условий.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "Название города, например 'Moscow' или 'Saint Petersburg'")
                }
            },
            required = listOf("city"),
        ),
    ) { req -> handleCollectWeather(req, client, store) }

    // 2) Текущая погода: последний снапшот из store; если store пуст — один свежий fetch.
    server.addTool(
        name = "get_current_weather",
        description = "Текущая погода в городе: последний сохранённый замер (или свежий, если " +
            "данных ещё нет). Быстрый ответ «сколько сейчас градусов». Не накапливает историю.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") { put("type", "string"); put("description", "Название города") }
            },
            required = listOf("city"),
        ),
    ) { req -> handleCurrentWeather(req, client, store) }

    // 3) Агрегат за период: avg/min/max температуры, сумма осадков, диапазон, число замеров.
    server.addTool(
        name = "get_weather_summary",
        description = "Агрегированная сводка погоды по городу за последние N часов (по умолчанию 24): " +
            "средняя/мин/макс температура, суммарные осадки, число замеров и временной диапазон. " +
            "Основано на накопленных снапшотах (периодический сбор / collect_weather).",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                putJsonObject("city") { put("type", "string"); put("description", "Название города") }
                putJsonObject("hours") {
                    put("type", "integer")
                    put("description", "Период агрегации в часах (по умолчанию 24)")
                    put("default", 24)
                }
            },
            required = listOf("city"),
        ),
    ) { req -> handleWeatherSummary(req, store) }
}

// ── handlers ──────────────────────────────────────────────────────────────────

private suspend fun handleCollectWeather(
    req: CallToolRequest, client: WeatherClient, store: WeatherStore,
): CallToolResult {
    val city = stringArg(req.arguments, "city")?.trim()
    if (city.isNullOrBlank()) return toolError("Параметр 'city' обязателен.")
    val snapshot = client.collect(city) ?: return toolError("Не удалось получить погоду для '$city'.")
    store.append(snapshot)
    return CallToolResult(content = listOf(TextContent(formatSnapshot(snapshot))), isError = false)
}

private suspend fun handleCurrentWeather(
    req: CallToolRequest, client: WeatherClient, store: WeatherStore,
): CallToolResult {
    val city = stringArg(req.arguments, "city")?.trim()
    if (city.isNullOrBlank()) return toolError("Параметр 'city' обязателен.")
    val latest = store.latest(city) ?: client.collect(city)?.also { store.append(it) }
        ?: return toolError("Нет данных для '$city'. Вызовите collect_weather.")
    return CallToolResult(content = listOf(TextContent(formatSnapshot(latest))), isError = false)
}

private fun handleWeatherSummary(req: CallToolRequest, store: WeatherStore): CallToolResult {
    val city = stringArg(req.arguments, "city")?.trim()
    if (city.isNullOrBlank()) return toolError("Параметр 'city' обязателен.")
    val hours = (numberArg(req.arguments, "hours")?.toInt())?.coerceIn(1, 24 * 30) ?: 24
    val now = System.currentTimeMillis()
    val from = now - hours * 3_600_000L
    val snaps = store.loadRange(city, from, now)
    if (snaps.isEmpty()) {
        return toolError("Нет накопленных данных для '$city' за последние $hours ч. Вызовите collect_weather или дождитесь периодического сбора.")
    }
    val agg = aggregate(snaps)
    val text = buildString {
        appendLine("Сводка погоды: $city (последние $hours ч, замеров: ${agg.count})")
        appendLine("Период: ${fmtTime(agg.from)} … ${fmtTime(agg.to)}")
        appendLine("Температура: средняя ${fmtTemp(agg.avgTemp)}, мин ${fmtTemp(agg.minTemp)}, макс ${fmtTemp(agg.maxTemp)}")
        appendLine("Осадки суммарно: ${fmtMm(agg.totalPrecip)}")
        appendLine("Преобладающие условия: ${weatherDescription(agg.dominantCode)}")
    }.trimEnd()
    return CallToolResult(content = listOf(TextContent(text)), isError = false)
}

// ── чистая агрегация (тестируется без IO — задача 11) ─────────────────────────

/** Результат агрегации списка снапшотов. */
internal data class WeatherAggregate(
    val count: Int,
    val from: Long,           // epoch millis самого раннего
    val to: Long,             // epoch millis самого позднего
    val avgTemp: Double,
    val minTemp: Double,
    val maxTemp: Double,
    val totalPrecip: Double,
    val dominantCode: Int,    // самый частый weatherCode
)

/**
 * Чистая функция агрегации снапшотов (без IO, без времени). Принимает непустой список.
 * Вынесена из handler'а для unit-тестирования (задача 11) — без mock'ов store/client.
 */
internal fun aggregate(snaps: List<WeatherSnapshot>): WeatherAggregate {
    require(snaps.isNotEmpty()) { "aggregate requires non-empty list" }
    val temps = snaps.map { it.temperatureCelsius }
    val codes = snaps.groupingBy { it.weatherCode }.eachCount()
    return WeatherAggregate(
        count = snaps.size,
        from = snaps.minOf { it.timestamp },
        to = snaps.maxOf { it.timestamp },
        avgTemp = temps.average(),
        minTemp = temps.min(),
        maxTemp = temps.max(),
        totalPrecip = snaps.sumOf { it.precipitationMm },
        dominantCode = codes.maxByOrNull { it.value }!!.key,
    )
}

// ── форматирование ────────────────────────────────────────────────────────────

private val TIME_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())

private fun formatSnapshot(s: WeatherSnapshot): String = buildString {
    appendLine("Погода: ${s.city} — ${fmtTime(s.timestamp)}")
    appendLine("Температура: ${fmtTemp(s.temperatureCelsius)}")
    appendLine("Ветер: ${fmtKph(s.windSpeedKph)}")
    appendLine("Осадки: ${fmtMm(s.precipitationMm)}")
    append("Условия: ${weatherDescription(s.weatherCode)}")
}.trimEnd()

private fun fmtTime(epochMillis: Long): String = TIME_FMT.format(Instant.ofEpochMilli(epochMillis))
private fun fmtTemp(c: Double): String = "${c.roundToInt()}°C"
private fun fmtKph(v: Double): String = "${v.roundToInt()} км/ч"
private fun fmtMm(v: Double): String = "${"%.1f".format(v)} мм"
```

### Правка `McpServerFactory.kt`
Расширить сигнатуру и вызвать регистрацию:
```kotlin
internal fun buildServer(
    githubToken: String?,
    weatherClient: WeatherClient,   // Day 18
    weatherStore: WeatherStore,     // Day 18
): Server {
    val server = Server(
        serverInfo = Implementation(name = "cli-agent-mcp", version = "0.1.0"),
        options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
    )
    registerGitHubTools(server, githubToken)
    registerWeatherTools(server, weatherClient, weatherStore)   // Day 18 — 3 новых tool'а
    return server
}
```
> `McpServerApp.runStdio`/`runHttp` получают `weatherClient`/`weatherStore` из `main()` (задача 06)
> и прокидывают в `buildServer(...)`. В этой задаче — лишь сигнатура/вызов; wiring — 06.

## Ключевые инварианты

- **Tool-ошибки → `toolError`** (`isError=true`), **не** exception — видны LLM для самокоррекции
  (например, «нет данных → позови collect_weather»). Конвенция MCP, как в `get_repo` (Day 17).
- **`hours` coercion `[1, 24*30]`** — защита от абсурда (0 или 10000 ч); default 24.
- **`aggregate()` — чистая функция** (без IO/времени) → unit-тестируется без mock'ов (задача 11).
  `require(snaps.isNotEmpty())` — контракт, handler вызывает только с непустым списком.
- **Форматирование отдельно** от агрегации — `aggregate` возвращает числа, текст — в handler'е. Так
  тесты проверяют математику, а не парсят строки.
- **`get_current_weather` fallback на fetch** — если store пуст (город новый), один свежий замер с
  append. Не заставляет LLM делать лишний `collect_weather`.
- **`dominantCode`** — самый частый weather_code за период («преобладающие условия»), не последний.
- **Все три tool'а в одном `registerWeatherTools`** — единая точка регистрации погодных tool'ов;
  Day 19 (композиция) добавит сюда же или в соседний файл `tools/`.

## Решения

- **On-demand `collect_weather` + auto-scheduler (05)** — оба способа «по расписанию». LLM может
  сама звать `collect_weather` («приходи каждый час»), и серверный loop копит автоматически. По
  подсказке куратора: периодичность выражена через tool-concept + background-loop.
- **`get_weather_summary` принимает `hours`, не диапазон дат** — проще для LLM (одно число),
  покрывает «регулярный summary за день/неделю». Диапазон `from..to` = `now - hours*3600_000`.
- **`get_current_weather` не пишет в store при наличии последнего** — иначе каждое «сколько сейчас»
  плодит дубли. Только fallback-fetch для нового города.
- **Кириллица в ответах** — демо/профиль русскоязычный (day-15 профиль RU); LLM переформулирует под
  запрос пользователя при необходимости.

## Критерии готовности

- `./gradlew :mcp-server:build` — green.
- Raw JSON-RPC `tools/list` → **ровно 4 tools**: `get_repo`, `collect_weather`,
  `get_current_weather`, `get_weather_summary` (с schema city/hours).
- Raw `tools/call get_weather_summary {city:"X"}` при пустом store → `isError:true` с подсказкой.
- `aggregate(listOf(...))` — unit-проверка avg/min/max/sum/dominant (задача 11).
- Никаких изменений в клиентском модуле `src/main/...`.

## Зависимости (задачи)

Использует 01 (factory), 02 (store), 03 (client). Wiring сигнатуры `buildServer` — задача 06.
Тестируется: `aggregate` — в 11, handlers — smoke в 12/13 (требуют сети/LLM).
