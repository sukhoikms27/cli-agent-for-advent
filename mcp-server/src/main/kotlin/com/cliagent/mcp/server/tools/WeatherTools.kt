package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.util.numberArg
import com.cliagent.mcp.server.util.stringArg
import com.cliagent.mcp.server.util.toolError
import com.cliagent.mcp.server.weather.WeatherClient
import com.cliagent.mcp.server.weather.WeatherScheduler
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
 * Погодные tools дня 18. Все read/write через [WeatherClient]/[WeatherStore]; tool-level ошибки →
 * [toolError] (isError=true, видны LLM), не exception (конвенция MCP). По подсказке куратора
 * «у MCP есть понятие tool» — «по расписанию» выражается и on-demand [collect_weather], и серверным
 * background-scheduler'ом (задача 05).
 *
 * `aggregate()` — чистая функция (без IO), вынесена для unit-тестирования (задача 11).
 */
internal fun registerWeatherTools(
    server: Server,
    client: WeatherClient,
    store: WeatherStore,
    scheduler: WeatherScheduler,
) {

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

// ── handlers подписок (R2) ────────────────────────────────────────────────────

private fun handleSubscribeWeather(
    req: CallToolRequest, scheduler: WeatherScheduler,
): CallToolResult {
    val city = stringArg(req.arguments, "city")?.trim()
    if (city.isNullOrBlank()) return toolError("Параметр 'city' обязателен.")
    val interval = (numberArg(req.arguments, "interval_minutes")?.toLong()) ?: 60L
    val result = scheduler.subscribe(city, interval) // coerce внутри (R1)
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
        subs.forEach {
            appendLine("  • ${it.city}: раз в ${it.intervalMinutes} мин — ${if (it.active) "активна" else "остановлена"}")
        }
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
    // Нет накопленных данных → один свежий замер с append (для нового города).
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
        return toolError(
            "Нет накопленных данных для '$city' за последние $hours ч. " +
                "Вызовите collect_weather или дождитесь периодического сбора."
        )
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
