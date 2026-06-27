# Задача 09. Тесты `WeatherClient` (Ktor `MockEngine`, без сети)

## Цель

Покрыть `WeatherClient` (geocode/fetchForecast/collect) юнит-тестами на `MockEngine` — без реальных
сетевых вызовов. Проверить parse happy-path, невалидный город, HTTP-ошибку, обработку отсутствующих
полей.

## Зависимости

03 (`WeatherClient`, `LatLon`). 07 (test-инфраструктура, `ktor-client-mock`).

## Тест-класс (новый)

`mcp-server/src/test/kotlin/com/cliagent/mcp/server/weather/WeatherClientTest.kt`

## Что реализовать

```kotlin
package com.cliagent.mcp.server.weather

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeatherClientTest {

    private fun client(respond: (String, HttpStatusCode) -> Unit): WeatherClient {
        val http = HttpClient(MockEngine { request ->
            val (body, status) = respond_(); // см. ниже — упрощённо: роутинг по URL-пути
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
        return WeatherClient(http)
    }
    // (в реальном тесте — MockEngine с роутингом: path.contains("geocoding") → geocoding-боди;
    //  path.contains("forecast") → forecast-боди. См. скетч ниже.)

    @Test
    fun `geocode parses first result`() = runTest {
        val c = geocodeForecastClient(
            geocoding = """{"results":[{"name":"Moscow","latitude":55.75,"longitude":37.62}]}""",
            forecast = """{"latitude":55.75,"longitude":37.62,"current":{"temperature_2m":18.4,"wind_speed_10m":12.3,"precipitation":0.0,"weather_code":2}}""",
        )
        val ll = c.geocode("Moscow")
        assertNotNull(ll)
        assertEquals(55.75, ll!!.lat)
        assertEquals(37.62, ll.lon)
    }

    @Test
    fun `geocode returns null when city not found`() = runTest {
        val c = geocodeForecastClient(geocoding = """{"results":[]}""", forecast = "")
        assertNull(c.geocode("Nowhere"))
    }

    @Test
    fun `fetchForecast parses snapshot`() = runTest {
        val c = geocodeForecastClient(
            geocoding = "", forecast =
            """{"latitude":55.75,"longitude":37.62,"current":{"temperature_2m":5.0,"wind_speed_10m":3.0,"precipitation":0.5,"weather_code":61}}""",
        )
        val s = c.fetchForecast(55.75, 37.62, "Moscow")
        assertNotNull(s)
        assertEquals(5.0, s!!.temperatureCelsius)
        assertEquals(0.5, s.precipitationMm)
        assertEquals(61, s.weatherCode)
    }

    @Test
    fun `collect composes geocode and forecast`() = runTest {
        val c = geocodeForecastClient(
            geocoding = """{"results":[{"name":"Moscow","latitude":55.75,"longitude":37.62}]}""",
            forecast = """{"current":{"temperature_2m":10.0,"weather_code":0}}""",
        )
        val s = c.collect("Moscow")
        assertNotNull(s)
        assertEquals(10.0, s!!.temperatureCelsius)
        assertEquals("Moscow", s.city)
    }

    @Test
    fun `collect returns null for city failing geocode`() = runTest {
        val c = geocodeForecastClient(geocoding = """{"results":[]}""", forecast = "")
        assertNull(c.collect("Nowhere"))
    }

    @Test
    fun `collect returns null on HTTP error`() = runTest {
        val c = geocodeForecastClient(geocoding = "", forecast = "", geocodingStatus = HttpStatusCode.InternalServerError)
        assertNull(c.collect("Moscow"))
    }

    @Test
    fun `city regex rejects path-like injection`() = runTest {
        val c = geocodeForecastClient(geocoding = """{"results":[]}""", forecast = "")
        // CITY_REGEX отсекает ../ и спецсимволы до запроса
        assertNull(c.geocode("../etc"))
        assertNull(c.geocode("a;rm"))
    }

    // ── helper: MockEngine с роутингом geocoding↔forecast ──────────────────────
    private fun geocodeForecastClient(
        geocoding: String,
        forecast: String,
        geocodingStatus: HttpStatusCode = HttpStatusCode.OK,
    ): WeatherClient {
        val http = HttpClient(MockEngine { request ->
            val body: String
            val status: HttpStatusCode
            when {
                request.url.encodedPath.contains("geocoding") -> { body = geocoding; status = geocodingStatus }
                request.url.encodedPath.contains("forecast") -> { body = forecast; status = HttpStatusCode.OK }
                else -> { body = ""; status = HttpStatusCode.NotFound }
            }
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
        return WeatherClient(http)
    }
}
```

> Скетч выше содержит две версии `client(...)`-хелпера — в реализации оставить **одну**
> (`geocodeForecastClient` с роутингом по URL-пути), убрать черновую `client(...)`.

## Что проверяет

| # | Кейс | Ожидание |
|---|---|---|
| 1 | geocode happy | `LatLon(55.75, 37.62)` (первый result) |
| 2 | geocode пустой results | `null` (город не найден) |
| 3 | fetchForecast parse | snapshot с заполненными полями |
| 4 | collect композиция | geocode+forecast → полный snapshot |
| 5 | collect при failed geocode | `null` (geocode отсекает до forecast) |
| 6 | collect при HTTP 500 | `null` (HTTP-ошибка трактуется как «нет данных») |
| 7 | CITY_REGEX path-injection | `../etc`, `a;rm` → `null` до запроса |

## Ключевые инварианты

- **`MockEngine`** (ktor-client-mock) — перехватывает HTTP-запросы, отдаёт заданный body/status.
  **Никаких реальных сетевых вызовов** — тесты детерминированы, оффлайн, быстры.
- **Роутинг по `request.url.encodedPath`** — geocoding-API и forecast-API на разных хостах/путях
  (`geocoding-api.../v1/search` vs `api.../v1/forecast`); MockEngine различает их по подстроке
  `geocoding`/`forecast` в path.
- **`runTest`** — suspend-функции клиента вызываются в тестовом coroutine-scope без реальной
  задержки.
- **Тест path-injection** — подтверждает `CITY_REGEX` как фронт-gate (задача 03): URL-кодирования
  Ktor достаточно для query, но regex отсекает очевидный мусор раньше (defense in depth, Week 04).

## Критерии готовности

- `./gradlew :mcp-server:test --tests "*WeatherClientTest"` — green (7 тестов).
- Тесты не делают реальных сетевых запросов (ни одного обращения к open-meteo.com).

## Зависимости (задачи)

03 (client). 07 (infra, ktor-client-mock). Независимо от 08/10/11.
