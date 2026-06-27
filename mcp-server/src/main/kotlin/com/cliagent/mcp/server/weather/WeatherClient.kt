package com.cliagent.mcp.server.weather

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клиент к Open-Meteo (geocoding + forecast), Day 18. Без API-ключа — не требует secrets, безопасен
 * для remote-сервера. Read-only. Источник данных для «периодического сбора» (задача 05) и
 * погодных tools (задача 04).
 *
 * Ошибки сети/парсинга/HTTP → `null` (handler в задаче 04 решает, вернуть tool-error или fallback).
 * `CancellationException` — re-throw (конвенция проекта: никогда не глотать, AGENTS.md).
 *
 * @param httpinjectable HttpClient (default — CIO + ContentNegotiation/json). В тестах (задача 09)
 *        подставляется HttpClient с MockEngine — без реальной сети.
 */
internal class WeatherClient(
    private val http: HttpClient = defaultClient(),
) {
    /** Геокодинг города → (lat, lon) или null (не найден / ошибка сети / невалидное имя). */
    suspend fun geocode(city: String): LatLon? {
        if (!CITY_REGEX.matches(city)) return null        // allowlist ДО подстановки в URL
        val resp = try {
            http.get("https://geocoding-api.open-meteo.com/v1/search") {
                url.parameters.append("name", city.trim())
                url.parameters.append("count", "1")
                url.parameters.append("language", "ru")
                url.parameters.append("format", "json")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (resp.status != HttpStatusCode.OK) return null
        val data = runCatching { resp.body<GeocodingResponse>() }.getOrNull() ?: return null
        val hit = data.results?.firstOrNull() ?: return null
        return LatLon(hit.latitude, hit.longitude)
    }

    /** Текущий замер по координатам → snapshot (timestamp = теперь). null при ошибке. */
    suspend fun fetchForecast(lat: Double, lon: Double, city: String): WeatherSnapshot? {
        val resp = try {
            http.get("https://api.open-meteo.com/v1/forecast") {
                url.parameters.append("latitude", lat.toString())
                url.parameters.append("longitude", lon.toString())
                url.parameters.append("current", "temperature_2m,wind_speed_10m,precipitation,weather_code")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
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

    fun close(): Unit = runCatching { http.close() }.let { }

    // ── internals ──────────────────────────────────────────────────────────────

    /** Allowlist имени города ДО подстановки в URL: латиница, кириллица, цифры, пробел, .-' */
    private val CITY_REGEX = Regex("^[A-Za-zА-Яа-яЁё0-9 .\\-']+$")
}

internal data class LatLon(val lat: Double, val lon: Double)

private fun defaultClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
}

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
