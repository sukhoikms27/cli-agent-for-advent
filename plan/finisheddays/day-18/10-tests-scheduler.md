# Задача 10. Тесты `WeatherScheduler` (`runTest` + fakes, без сети/FS)

## Цель

Покрыть `WeatherScheduler` юнит-тестами с виртуальным временем (`runTest`) и fake'ами client/store —
без реальной сети и FS. Проверить: N циклов сбора → N*cities снапшотов; изоляция ошибок по городам;
корректная отмена (cancellation).

## Зависимости

05 (`WeatherScheduler`). 03 (`WeatherClient`) — но в тесте подменяется fake'ом; 02 (`WeatherStore`)
— тоже fake. 07 (test-инфраструктура, `kotlinx-coroutines-test`).

## Подход: fakes вместо mock'ов

`WeatherClient` и `WeatherStore` — конкретные классы (не интерфейсы). Для теста scheduler'а
достаточно **fake'ов**: либо (а) сделать их open + override `collect`/`append` (mockk `spyk`), либо
(б) [предпочтительно] в задаче 02/03 заложить минимальные интерфейсы/`open`-методы под тест. В
скетче ниже — mockk-вариант (`spyk` + `coEvery`), не требующий правок прод-кода.

## Тест-класс (новый)

`mcp-server/src/test/kotlin/com/cliagent/mcp/server/weather/WeatherSchedulerTest.kt`

## Что реализовать

```kotlin
package com.cliagent.mcp.server.weather

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeatherSchedulerTest {

    @Test
    fun `collects each city once per cycle`() = runTest {
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns snap("Moscow", 1.0)
        coEvery { client.collect("Kazan") } returns snap("Kazan", 2.0)
        val store = spyk(WeatherStore(tempDir()))
        val scheduler = WeatherScheduler(client, store, listOf("Moscow", "Kazan"), intervalSeconds = 1)

        scheduler.start(this)
        runCurrent()                          // выполнить немедленный первый цикл
        scheduler.stop()

        assertEquals(1, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
        assertEquals(1, store.loadRange("Kazan", 0, Long.MAX_VALUE).size)
    }

    @Test
    fun `per-cycle isolation — failed city does not block others`() = runTest {
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns null          // имитация сбоя geocode
        coEvery { client.collect("Kazan") } returns snap("Kazan", 2.0)
        val store = spyk(WeatherStore(tempDir()))
        val scheduler = WeatherScheduler(client, store, listOf("Moscow", "Kazan"), intervalSeconds = 1)

        scheduler.start(this)
        runCurrent()
        scheduler.stop()

        assertEquals(0, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)   // Moscow упал, не записан
        assertEquals(1, store.loadRange("Kazan", 0, Long.MAX_VALUE).size)    // Kazan собран несмотря на сбой Moscow
    }

    @Test
    fun `multiple cycles accumulate snapshots over virtual time`() = runTest {
        val client = mockk<WeatherClient>()
        var n = 0
        coEvery { client.collect("Moscow") } answers { n++; snap("Moscow", n.toDouble()) }
        val store = spyk(WeatherStore(tempDir()))
        val scheduler = WeatherScheduler(client, store, listOf("Moscow"), intervalSeconds = 60)

        scheduler.start(this)
        runCurrent()                          // цикл 1 (немедленно)
        advanceTimeBy(60_000)                 // +60с виртуального времени → цикл 2
        runCurrent()
        advanceTimeBy(60_000)                 // → цикл 3
        runCurrent()
        scheduler.stop()

        val snaps = store.loadRange("Moscow", 0, Long.MAX_VALUE)
        assertEquals(3, snaps.size)           // 3 цикла → 3 снапшота
        assertEquals(listOf(1.0, 2.0, 3.0), snaps.map { it.temperatureCelsius })
    }

    @Test
    fun `stop cancels loop cleanly`() = runTest {
        val client = mockk<WeatherClient>()
        coEvery { client.collect(any()) } returns snap("Moscow", 1.0)
        val store = spyk(WeatherStore(tempDir()))
        val scheduler = WeatherScheduler(client, store, listOf("Moscow"), intervalSeconds = 1)

        scheduler.start(this)
        runCurrent()
        scheduler.stop()
        advanceTimeBy(1_000_000)              // много виртуального времени — новых сборов быть не должно
        runCurrent()

        assertEquals(1, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
    }

    @Test
    fun `disabled when no cities`() = runTest {
        val client = mockk<WeatherClient>()
        val store = spyk(WeatherStore(tempDir()))
        val scheduler = WeatherScheduler(client, store, emptyList(), intervalSeconds = 60)

        scheduler.start(this)
        runCurrent()
        scheduler.stop()

        assertTrue(store.loadRange("Moscow", 0, Long.MAX_VALUE).isEmpty())
    }

    private fun snap(city: String, temp: Double) = WeatherSnapshot(
        timestamp = System.currentTimeMillis(), city = city, temperatureCelsius = temp,
    )

    private fun tempDir(): java.nio.file.Path =
        java.nio.file.Files.createTempDirectory("ws-test")
}
```

> **Примечание по `runTest` API:** `runCurrent()` / `advanceTimeBy()` — методы `TestScope`
> (kotlinx-coroutines-test 1.11). `delay()` внутри scheduler'а управляется виртуальным временем,
> реального ожидания нет. Если в версии 1.11 API отличается — использовать `advanceTimeBy` +
> `runCurrent` эквивалент или `testScheduler`. Проверить при кодировании.

## Что проверяет

| # | Кейс | Ожидание |
|---|---|---|
| 1 | один цикл, 2 города | по 1 снапшоту на город |
| 2 | один город падает (collect→null) | другой город всё равно собран (изоляция) |
| 3 | 3 цикла (виртуальное +60с×2) | 3 снапшота с нарастающей температурой |
| 4 | stop() | loop отменён, новых сборов после нет |
| 5 | empty cities | scheduler disabled, 0 сборов |

## Ключевые инварианты

- **`runTest` + виртуальное время** — `delay(intervalSeconds*1000)` внутри scheduler'а не ждёт
  реально; `advanceTimeBy` двигает виртуальные часы. Тесты мгновенны.
- **Fake store через `spyk`** — реальный `WeatherStore` (tmp dir) с реальной FS, но `loadRange`
  читает накопленное. Альтернатива: in-memory fake-класс; `spyk(WeatherStore(tempDir()))` проще.
- **Изоляция ошибок (тест 2)** — подтверждает инвариант scheduler'а (задача 05): `try/catch` в
  `collectAll` per-city; упавший `Moscow` не блокирует `Kazan`.
- **Cancellation (тест 4)** — после `stop()` loop отменяется на `delay`; новые `advanceTimeBy` не
  порождают сборов. `CancellationException` корректно пробрасывается (не валит тест).

## Решения

- **Fakes/mockk, а не интерфейсы** — добавлять интерфейсы под тест — over-engineering для CLI-тула.
  `mockk` для client (его поведение — цель мокинга) + `spyk(WeatherStore)` (реальная FS полезна —
  проверяем именно накопление).
- **`tempDir()` через `Files.createTempDirectory`** — не `@TempDir` (тот — поле экземпляра/метода в
  JUnit 5; для нескольких тестов проще создавать в helper-методе). ОС уберёт при cleanup.

## Критерии готовности

- `./gradlew :mcp-server:test --tests "*WeatherSchedulerTest"` — green (5 тестов).
- Тесты не делают реальных сетевых вызовов и не зависят от реального времени.

## Зависимости (задачи)

05 (scheduler). 07 (infra). Независимо от 08/09/11.
