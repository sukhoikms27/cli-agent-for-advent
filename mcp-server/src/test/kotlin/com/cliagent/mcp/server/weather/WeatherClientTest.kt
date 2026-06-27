package com.cliagent.mcp.server.weather

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WeatherClientTest {

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
            geocoding = "",
            forecast = """{"latitude":55.75,"longitude":37.62,"current":{"temperature_2m":5.0,"wind_speed_10m":3.0,"precipitation":0.5,"weather_code":61}}""",
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
        val c = geocodeForecastClient(
            geocoding = "", forecast = "", geocodingStatus = HttpStatusCode.InternalServerError,
        )
        assertNull(c.collect("Moscow"))
    }

    @Test
    fun `city regex rejects path-like injection`() = runTest {
        val c = geocodeForecastClient(geocoding = """{"results":[]}""", forecast = "")
        // CITY_REGEX отсекает ../ и спецсимволы ДО запроса
        assertNull(c.geocode("../etc"))
        assertNull(c.geocode("a;rm"))
    }

    // ── helper: MockEngine с роутингом geocoding↔forecast ──────────────────────────
    // URL geocoding-API: https://geocoding-api.open-meteo.com/v1/search?...  (path = /v1/search)
    // URL forecast-API:  https://api.open-meteo.com/v1/forecast?...          (path = /v1/forecast)
    // encodedPath не содержит host, поэтому различаем по полному URL (по подстроке host/path).
    private fun geocodeForecastClient(
        geocoding: String,
        forecast: String,
        geocodingStatus: HttpStatusCode = HttpStatusCode.OK,
    ): WeatherClient {
        val http = HttpClient(MockEngine { request ->
            val full = request.url.toString()
            val body: String
            val status: HttpStatusCode
            when {
                full.contains("geocoding-api") -> { body = geocoding; status = geocodingStatus }
                full.contains("forecast") -> { body = forecast; status = HttpStatusCode.OK }
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
