# Задача 03. `WeatherClient` — Open-Meteo (geocoding + forecast) поверх Ktor

## Цель

HTTP-клиент к Open-Meteo (бесплатно, без API-ключа): по имени города → координаты (geocoding), по
координатам → текущий замер (forecast). Возвращает `WeatherSnapshot`. Не авторизует, не пишет в
store (это задача storage'а 02 / tools 04). Источник данных для **«периодического сбора»**.

> **Почему Open-Meteo:** без auth → не ломает security-модель remote-сервера (в отличие от GitHub
> PAT). Мягкие лимиты (~10k/день), на фоне «раз в час» нагрузки нет. Вариант #2 из day17-results.

## Зависимости

Задача 02 (`WeatherSnapshot`). Образец Ktor-клиента в модуле — бывший `buildServer()` HttpClient
(теперь `tools/GitHubTools.kt`, Day 17): CIO + ContentNegotiation/json, `header(...)`, `resp.body<T>()`
с `runCatching` fallback, `CancellationException` rethrow.

## API (Open-Meteo)

**Geocoding** — `GET https://geocoding-api.open-meteo.com/v1/search?name=<city>&count=1&language=ru&format=json`
```json
{ "results": [ { "name": "Moscow", "latitude": 55.75, "longitude": 37.62, "country": "Russia" } ] }
```
`results` пуст/отсутствует → город не найден → `null`.

**Forecast (current)** — `GET https://api.open-meteo.com/v1/forecast?latitude=<lat>&longitude=<lon>&current=temperature_2m,wind_speed_10m,precipitation,weather_code`
```json
{
  "latitude": 55.75, "longitude": 37.62,
  "current": { "temperature_2m": 18.4, "wind_speed_10m": 12.3, "precipitation": 0.0, "weather_code": 2 }
}
```

## Файл (новый)

`weather/WeatherClient.kt`

## Что реализовать

```kotlin
package com.cliagent.mcp.server.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клиент к Open-Meteo (geocoding + forecast), Day 18. Без API-ключа — не требует secrets, безопасен
 * для remote-сервера. Read-only.
 *
 * Ошибки сети/парсинга/HTTP → null (handler в задаче 04 решает, вернуть tool-error или fallback).
 * `CancellationException` — re-throw (конвенция проекта: никогда не глотать).
 *
 * `close()` — закрыть HttpClient (вызывает McpServerApp в lifecycle; per-Server, как GitHub-клиент).
 */
internal class WeatherClient(
    private val http: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    },
) {
    /** Геокодинг города → (lat, lon) или null (не найден / ошибка сети). */
    suspend fun geocode(city: String): LatLon? {
        if (!CITY_REGEX.matches(city)) return null        // allowlist до подстановки в URL
        val resp = runCatching {
            http.get("https://geocoding-api.open-meteo.com/v1/search") {
                url.parameters.append("name", city.trim())
                url.parameters.append("count", "1")
                url.parameters.append("language", "ru")
                url.parameters.append("format", "json")
            }
        }.getOrNull() ?: return null
        if (resp.status != HttpStatusCode.OK) return null
        val data = runCatching { resp.body<GeocodingResponse>() }.getOrNull() ?: return null
        val hit = data.results?.firstOrNull() ?: return null
        return LatLon(hit.latitude, hit.longitude)
    }

    /** Текущий замер по координатам → snapshot (timestamp = теперь). null при ошибке. */
    suspend fun fetchForecast(lat: Double, lon: Double, city: String): WeatherSnapshot? {
        val resp = runCatching {
            http.get("https://api.open-meteo.com/v1/forecast") {
                url.parameters.append("latitude", lat.toString())
                url.parameters.append("longitude", lon.toString())
                url.parameters.append("current", "temperature_2m,wind_speed_10m,precipitation,weather_code")
            }
        }.getOrNull() ?: return null
        if (resp.status != HttpStatusCode.OK) return null
        val data = runCatching { resp.body<ForecastResponse>() }.getOrNull() ?: return null
        val cur = data.current ?: return null
        return WeatherSnapshot(
            timestamp = System.currentTimeMillis(),
            city = city,
            latitude = lat,
            longitude = lon,
            temperatureCelsius = cur.temperature2m,
            windSpeedKph = cur.windSpeed10m,
            precipitationMm = cur.precipitation,
            weatherCode = cur.weatherCode,
            source = "open-meteo",
        )
    }

    /** Композиция: geocode + forecast одним вызовом (для tools/scheduler). null, если город не найден. */
    suspend fun collect(city: String): WeatherSnapshot? {
        val ll = geocode(city) ?: return null
        return fetchForecast(ll.lat, ll.lon, city)
    }

    fun close() = runCatching { http.close() }

    // ── internals ──────────────────────────────────────────────────────────────

    /** Allowlist имени города до подстановки в URL: латиница, кириллица, цифры, пробел, .-' */
    private val CITY_REGEX = Regex("^[A-Za-zА-Яа-яЁё0-9 .\\-']+$")
}

internal data class LatLon(val lat: Double, val lon: Double)

@Serializable
private data class GeocodingResponse(
    val results: List<GeoHit>? = null,
)

@Serializable
private data class GeoHit(
    val name: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val country: String? = null,
)

@Serializable
private data class ForecastResponse(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val current: ForecastCurrent? = null,
)

@Serializable
private data class ForecastCurrent(
    @SerialName("temperature_2m") val temperature2m: Double = 0.0,
    @SerialName("wind_speed_10m") val windSpeed10m: Double = 0.0,
    val precipitation: Double = 0.0,
    @SerialName("weather_code") val weatherCode: Int = 0,
)
```

> **Параметры query** — через `url.parameters.append` (НЕ интерполяция в строку URL): name города
> корректно URL-кодируется Ktor'ом, защиты от injection в query достаточно. `CITY_REGEX` —
> дополнительный фронт-gate (отсечь очевидный мусор типа `../`, `;rm -rf`).

## Ключевые инварианты

- **`CancellationException` — re-throw.** В скетче `runCatching` ловит generic exceptions, но
  `CancellationException` — `kotlinx.coroutines`-сигнал отмены; его глотать запрещено (AGENTS.md).
  Реализация: `runCatching { ... }.onFailure { if (it is CancellationException) throw it }` ИЛИ
  явный try/catch с `if (e is CancellationException) throw e` перед `getOrNull()` — **обязательно**
  проверить при кодировании (образец: `handleGetRepo` GitHub-сервера, Day 17).
- **Allowlist `CITY_REGEX` ДО URL** — фронт-gate против path/query-injection. Тот же принцип, что
  `NAME_REGEX` для GitHub owner/repo (Day 17, Week 04 security).
- **Ошибки → `null`, не exception.** Сетевая/HTTP/parse-ошибка — это штатный «нет данных», handler
  (04) вернёт tool-error; сервер не падает. Протокольные ошибки (MCP-транспорт) — отдельно, их не
  касается.
- **DTO с `@SerialName` + defaults** — snake_case API → camelCase Kotlin, `ignoreUnknownKeys=true`
  пропускает лишнее (AGENTS.md schema evolution).
- **HttpClient per-`WeatherClient`** — CIO + pool; `close()` закрывает (вызывает McpServerApp в
  lifecycle, per-Server, как GitHub-клиент в `registerGitHubTools`).
- **`timestamp = System.currentTimeMillis()`** — фиксируем момент замера на клиенте (server-side clock),
  не из API (там нет epoch-millis в current-блоке).

## Решения

- **Geocoding отдельно от forecast** — переиспользование: tools могут звать `collect` (geocode+forecast),
  а `get_current_weather` — кешировать lat/lon в будущем. `collect` = композиция, удобная для
  scheduler'а (05) и `collect_weather` (04).
- **`language=ru`** в geocoding — города возвращаются/парсятся на русском (демо на русском, day-15
  профиль RU). Не влияет на lat/lon.
- **`current=...` явно** — запрашиваем ровно нужные переменные; меньше payload, нет лишних полей
  (token-cost в ответе tools минимален).
- **Без retry/backoff** — на фоне «раз в час» единичный сбой некритичен (scheduler повторит через
  интервал; on-demand tool вернёт null → tool-error, LLM сообщит пользователю). Усложнение не оправдано.

## Критерии готовности

- `./gradlew :mcp-server:build` — green (компилируется).
- Unit-тест в задаче 09 (Ktor `MockEngine`, без сети): geocode parse, forecast parse, невалидный
  город → `null`, HTTP-ошибка → `null`, `collect("Moscow")` → snapshot с заполненными полями.
- `CITY_REGEX` пропускает `"Moscow"`/`"Saint Petersburg"`/`"Санкт-Петербург"`, отсеивает `"../x"`
  и `"a;rm"`.

## Зависимости (задачи)

Использует 02 (`WeatherSnapshot`). Используется в 04 (tools) и 05 (scheduler). Тестируется в 09.
