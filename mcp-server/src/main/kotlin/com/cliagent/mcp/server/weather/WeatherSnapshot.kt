package com.cliagent.mcp.server.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Один замер погоды в момент времени (Day 18). Поля с defaults — schema evolution: старый JSON без
 * нового поля грузится бесшовно (AGENTS.md). Nullable там, где API может не отдать значение.
 *
 * @param timestamp epoch millis момента замера (для range-фильтра в summary)
 * @param city       человекочитаемое имя города (как пришло от LLM/геокодера)
 * @param latitude, longitude координаты (из геокодера; фиксируют точку замера)
 * @param temperatureCelsius температура (current)
 * @param windSpeedKph     скорость ветра (current)
 * @param precipitationMm  осадки (current)
 * @param weatherCode      WMO code (см. [weatherDescription]); числовой — компактнее в JSON
 * @param source           источник данных ("open-meteo") — для отладки/сравнения в будущем
 */
@Serializable
internal data class WeatherSnapshot(
    val timestamp: Long,
    val city: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerialName("temperature_celsius") val temperatureCelsius: Double = 0.0,
    @SerialName("wind_speed_kph") val windSpeedKph: Double = 0.0,
    @SerialName("precipitation_mm") val precipitationMm: Double = 0.0,
    @SerialName("weather_code") val weatherCode: Int = 0,
    val source: String = "open-meteo",
)

/** WMO weather-code → человекочтение описание (для сводки в tool-ответе). */
internal fun weatherDescription(code: Int): String = when (code) {
    0 -> "Ясно"
    1, 2, 3 -> "Преимущественно ясно / переменная облачность"
    45, 48 -> "Туман"
    51, 53, 55 -> "Морось"
    56, 57 -> "Ледяная морось"
    61, 63, 65 -> "Дождь"
    66, 67 -> "Ледяной дождь"
    71, 73, 75 -> "Снег"
    77 -> "Снежные зёрна"
    80, 81, 82 -> "Ливень"
    85, 86 -> "Снежный ливень"
    95 -> "Гроза"
    96, 99 -> "Гроза с градом"
    else -> "Неизвестно ($code)"
}
