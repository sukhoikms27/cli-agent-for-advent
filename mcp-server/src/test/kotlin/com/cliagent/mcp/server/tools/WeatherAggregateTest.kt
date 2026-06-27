package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.weather.WeatherSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class WeatherAggregateTest {

    private fun snap(
        ts: Long, temp: Double, precip: Double = 0.0, code: Int = 0,
    ) = WeatherSnapshot(
        timestamp = ts, city = "X", temperatureCelsius = temp,
        precipitationMm = precip, weatherCode = code,
    )

    @Test
    fun `average min max temperature over single snapshot`() {
        val agg = aggregate(listOf(snap(100, 15.0)))
        assertEquals(1, agg.count)
        assertEquals(15.0, agg.avgTemp)
        assertEquals(15.0, agg.minTemp)
        assertEquals(15.0, agg.maxTemp)
    }

    @Test
    fun `average min max over multiple snapshots`() {
        val snaps = listOf(snap(100, 10.0), snap(200, 20.0), snap(300, 15.0))
        val agg = aggregate(snaps)
        assertEquals(3, agg.count)
        assertEquals(15.0, agg.avgTemp)
        assertEquals(10.0, agg.minTemp)
        assertEquals(20.0, agg.maxTemp)
    }

    @Test
    fun `total precipitation sums all snapshots`() {
        val snaps = listOf(snap(100, 10.0, precip = 0.5), snap(200, 12.0, precip = 1.5))
        assertEquals(2.0, aggregate(snaps).totalPrecip)
    }

    @Test
    fun `dominant code is most frequent weather_code`() {
        val snaps = listOf(
            snap(100, 10.0, code = 0), snap(200, 11.0, code = 61),
            snap(300, 12.0, code = 61), snap(400, 13.0, code = 2),
        )
        assertEquals(61, aggregate(snaps).dominantCode)
    }

    @Test
    fun `from-to span earliest and latest timestamps`() {
        val snaps = listOf(snap(500, 10.0), snap(100, 11.0), snap(300, 12.0))
        val agg = aggregate(snaps)
        assertEquals(100, agg.from)
        assertEquals(500, agg.to)
    }

    @Test
    fun `empty list throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { aggregate(emptyList()) }
    }

    @Test
    fun `negative temperatures handled correctly`() {
        val snaps = listOf(snap(100, -5.0), snap(200, -15.0), snap(300, 0.0))
        val agg = aggregate(snaps)
        assertEquals(-15.0, agg.minTemp)
        assertEquals(0.0, agg.maxTemp)
        assertEquals(-6.67, (agg.avgTemp * 100).roundToInt() / 100.0)
    }
}
