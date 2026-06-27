# Задача 11. Тесты `aggregate()` + сборный обзор тестов дня 18

## Цель

1. Покрыть чистую функцию `aggregate()` (задача 04) юнит-тестами — без mock'ов (нет IO).
2. Свести все тест-классы дня 18 в единую обзорную таблицу + регрессионные гарантии.

## Зависимости

04 (`aggregate`, `WeatherAggregate`, `WeatherSnapshot`). 07 (test-инфраструктура).

## Тест-класс (новый)

`mcp-server/src/test/kotlin/com/cliagent/mcp/server/tools/WeatherAggregateTest.kt`
(в пакете `tools`, т.к. `aggregate` объявлен `internal` в `tools/WeatherTools.kt`)

## Что реализовать

```kotlin
package com.cliagent.mcp.server.tools

import com.cliagent.mcp.server.weather.WeatherSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class WeatherAggregateTest {

    private fun snap(ts: Long, temp: Double, precip: Double = 0.0, code: Int = 0) = WeatherSnapshot(
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
```

## Что проверяет

| # | Кейс | Ожидание |
|---|---|---|
| 1 | один снапшот | avg=min=max=его температура |
| 2 | несколько | avg/min/max корректны |
| 3 | осадки | суммирование всех precipitation |
| 4 | dominant code | самый частый weather_code |
| 5 | from/to | мин/макс timestamp (независимо от порядка) |
| 6 | пустой список | `IllegalArgumentException` (контракт `require`) |
| 7 | отрицательные температуры | корректные min/max/avg |

## Сборный обзор тестов дня 18

| # | Тест-класс (модуль `:mcp-server`) | Кол-во | Что проверяет | Задача |
|---|---|---|---|---|
| 1 | `weather/WeatherStoreTest` | 8 | append/loadRange/latest, atomic write, slugify, schema evolution, corruption | 08 |
| 2 | `weather/WeatherClientTest` | 7 | geocode/forecast/collect parse, null на ошибку/инъекцию (MockEngine) | 09 |
| 3 | `weather/WeatherSchedulerTest` | 5 | циклы сбора, изоляция по городам, cancellation, disabled (runTest) | 10 |
| 4 | `tools/WeatherAggregateTest` | 7 | aggregate avg/min/max/sum/dominant/empty/negative | 11 |
| | **Итого новых** | **~27** | | |

## Регрессионные гарантии

Существующие тесты, которые **остаются зелёными** (Day 18 их не трогает):

| Тест-класс (корневой модуль) | Почему не трогается |
|---|---|
| `McpClientServerIntegrationTest` (Day 17, gated) | stdio E2E — сервер меняется (рефактор 01), но протокол MCP/`get_repo` идентичен; raw `tools/list`/`tools/call` behaviour сохранено |
| `McpClientHttpE2ETest` (Day 17-vps, gated) | http E2E — bearer-auth/transport не меняются; добавляются tools, но существующий `get_repo` работает |
| `AgentToolUseLoopTest`, `McpToolMappingTest`, `ToolCallSerializationTest` (Day 17) | агентский loop/mapping — на клиенте 0 diff; новые tools авто-подхватятся |
| Все прочие тесты корня (дни 1–17) | Day 18 не трогает клиентский модуль `src/main/...` |

## Ключевые инварианты

- **`aggregate` — чистая функция** (нет IO, нет времени) → тестируется напрямую без mock'ов/store.
  `require(snaps.isNotEmpty())` — явный контракт; тест 6 проверяет исключение.
- **Тест в пакете `tools`** — `aggregate` объявлен `internal` в `tools/WeatherTools.kt` (задача 04);
  тест в том же пакете имеет доступ. Пакет теста = пакет прод-кода (конвенция).
- **Округление avg в тесте 7** — `double`-сравнение через `(x*100).roundToInt()/100.0` (2 знака),
  не `assertEquals(-6.6666...)` (float-погрешность).

## Решения

- **`aggregate` в `tools/`, не в `weather/`** — она — domain-логика tool-ответа `get_weather_summary`
  (агрегация «для сводки»), а не метод домена погоды. Но если при реализации окажется, что её
  переиспользуют scheduler/client — перенести в `weather/`; для Day 18 — в `tools/` (ближе к
  point-of-use). Тест следует за прод-кодом.
- **Без property-based тестов** — 7 кейсов покрывают математику; PB-тестирование избыточно для CLI.

## Критерии готовности

- `./gradlew :mcp-server:test` — все ~27 новых тестов green (4 тест-класса: 08/09/10/11).
- Корневой `./gradlew test` — 0 регрессий (Day 18 не трогает корневой main/test).

## Зависимости (задачи)

04 (aggregate). 07 (infra). Независимо от 08/09/10. Обзорный документ для всех тестов дня.
