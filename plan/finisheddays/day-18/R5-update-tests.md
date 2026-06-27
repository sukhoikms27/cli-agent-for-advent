# Задача R5. Переписать `WeatherSchedulerTest` + финальная верификация

## Цель

1. Переписать `WeatherSchedulerTest` (задача 10) под `WeatherScheduler` v2 (R1): subscribe/unsubscribe/list,
   per-city интервал, update-рестарт job, delay-first.
2. Финальная верификация всего Day 18 (R0–R4): полный `clean build`, smoke `tools/list`=7, 0 регрессий.

> Переработка задачи 10 (тесты) + задачи 12 (верификация).

## Зависимости

R1 (`WeatherScheduler` v2). 07 (test-инфра: mockk, runTest). Образец — `WeatherSchedulerTest` задачи 10.

## Файл

`mcp-server/src/test/kotlin/com/cliagent/mcp/server/weather/WeatherSchedulerTest.kt` — полная перезапись.

## Что реализовать (новые тесты)

```kotlin
package com.cliagent.mcp.server.weather

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WeatherSchedulerTest {

    // Scheduler на тестовом scope + dispatcher'е; runCurrent/advanceTimeBy управляют per-city jobs.
    private fun TestScope.makeScheduler(
        client: WeatherClient, store: WeatherStore, dispatcher: TestDispatcher,
    ): WeatherScheduler = WeatherScheduler(client, store, CoroutineScope(SupervisorJob()), dispatcher)

    @Test
    fun `subscribe registers and list shows it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns snap("Moscow", 1.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        assertTrue(scheduler.list().isEmpty())
        scheduler.subscribe("Moscow", intervalMinutes = 60)
        assertEquals(1, scheduler.list().size)
        assertEquals("Moscow", scheduler.list().first().city)
        scheduler.stopAll()
    }

    @Test
    fun `unsubscribe removes subscription and stops job`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns snap("Moscow", 1.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60)
        assertTrue(scheduler.unsubscribe("Moscow"))
        assertTrue(scheduler.list().isEmpty())
        assertFalse(scheduler.unsubscribe("Moscow")) // уже нет → false
        scheduler.stopAll()
    }

    @Test
    fun `delay-first — no collection before interval elapses`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns snap("Moscow", 1.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60) // 60 мин = 3_600_000 мс
        advanceTimeBy(3_000_000) // 50 мин (< interval) — сбора быть не должно
        runCurrent()
        assertEquals(0, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
        scheduler.stopAll()
    }

    @Test
    fun `first collection after interval elapses`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns snap("Moscow", 1.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60)
        advanceTimeBy(3_600_000) // ровно интервал → 1 сбор
        runCurrent()
        assertEquals(1, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
        scheduler.stopAll()
    }

    @Test
    fun `per-city intervals are independent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns snap("Moscow", 1.0)
        coEvery { client.collect("Kazan") } returns snap("Kazan", 2.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60)  // час
        scheduler.subscribe("Kazan", intervalMinutes = 30)   // полчаса
        advanceTimeBy(30 * 60_000L) // 30 мин → только Kazan
        runCurrent()
        assertEquals(0, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
        assertEquals(1, store.loadRange("Kazan", 0, Long.MAX_VALUE).size)
        advanceTimeBy(30 * 60_000L) // ещё 30 мин (итого 60) → Moscow×1, Kazan×2
        runCurrent()
        assertEquals(1, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
        assertEquals(2, store.loadRange("Kazan", 0, Long.MAX_VALUE).size)
        scheduler.stopAll()
    }

    @Test
    fun `re-subscribe restarts job with new interval`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns snap("Moscow", 1.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60)
        scheduler.subscribe("Moscow", intervalMinutes = 15) // рестарт: новый интервал
        assertEquals(1, scheduler.list().size)               // одна подписка (замена, не дубль)
        assertEquals(15L, scheduler.list().first().intervalMinutes)
        scheduler.stopAll()
    }

    @Test
    fun `error in one city does not affect others`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns null    // имитация сбоя
        coEvery { client.collect("Kazan") } returns snap("Kazan", 2.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60)
        scheduler.subscribe("Kazan", intervalMinutes = 60)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(0, store.loadRange("Moscow", 0, Long.MAX_VALUE).size) // Moscow падал, не записан
        assertEquals(1, store.loadRange("Kazan", 0, Long.MAX_VALUE).size)  // Kazan собран
        assertTrue(scheduler.list().any { it.city == "Moscow" })           // подписка Moscow жива (job не убит)
        scheduler.stopAll()
    }

    @Test
    fun `stopAll cancels every subscription`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect(any()) } returns snap("X", 1.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60)
        scheduler.subscribe("Kazan", intervalMinutes = 60)
        scheduler.stopAll()
        assertTrue(scheduler.list().isEmpty())
        advanceTimeBy(10_000_000) // много времени — новых сборов нет
        runCurrent()
    }

    private fun snap(city: String, temp: Double) = WeatherSnapshot(
        timestamp = System.currentTimeMillis(), city = city, temperatureCelsius = temp,
    )

    private fun tempDir() = Files.createTempDirectory("ws-test")
}
```

## Что проверяет (8 тестов)

| # | Кейс | Ожидание |
|---|---|---|
| 1 | subscribe → list | подписка видна (1 элемент) |
| 2 | unsubscribe | удаляется; повторный → false |
| 3 | delay-first, время < interval | **0** замеров |
| 4 | время = interval | **1** замер |
| 5 | per-city интервалы | разные города собираются по своему расписанию |
| 6 | re-subscribe (обновление интервала) | одна подписка, новый интервал (старая job отменена) |
| 7 | изоляция ошибок | упавший Moscow не блокирует Kazan; подписка Moscow жива |
| 8 | stopAll | все подписки отменены, новых сборов нет |

## Ключевые инварианты

- **`TestScope.makeScheduler` helper** — scheduler на `CoroutineScope(SupervisorJob())` + тестовый
  dispatcher; `runCurrent`/`advanceTimeBy` управляют per-city jobs через `StandardTestDispatcher`.
- **`delay-first` тесты (3, 4)** — прямая проверка решения пользователя «ждать интервал»: за время
  `< interval` — 0 замеров, при `= interval` — 1 замер. Это отличает R1 от старого scheduler'а (05),
  который собирал немедленно.
- **`coVerify` не используется** (упрощено) — вместо него проверка через `store.loadRange` (побочный
  эффект `collectOne` → `store.append`). Надёжнее: проверяет именно накопление, а не факт вызова mock'а.
- **Изоляция ошибок (7)** — `collect("Moscow") returns null`, но подписка **остаётся в реестре**
  (`list` её показывает) — job не убит сбоем, продолжит попытки каждый интервал.

## Финальная верификация (после R1–R4)

```bash
# 1. Все тесты модуля :mcp-server (R5 переписанный WeatherSchedulerTest + 08/09/11 без изменений)
./gradlew :mcp-server:test
# → 8 (R5) + 8 (08) + 7 (09) + 7 (11) = 30 тестов green

# 2. Полная сборка обоих модулей (0 регрессий дней 1-17)
./gradlew clean build

# 3. Fat-jar + дистрибутив
./gradlew :mcp-server:shadowJar :mcp-server:installDist
```

### Smoke (raw JSON-RPC)
```bash
# tools/list = 7 tools
printf '%s\n%s\n' \
'{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"s","version":"0"}}}' \
'{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
| mcp-server/build/install/mcp-server/bin/mcp-server
# → get_repo, collect_weather, get_current_weather, get_weather_summary,
#   subscribe_weather, list_weather_subscriptions, unsubscribe_weather

# subscribe (agent-driven, без env!)
printf '%s\n%s\n%s\n' \
'{"jsonrpc":"2.0","id":1,"method":"initialize",...}' \
'{"jsonrpc":"2.0","method":"notifications/initialized"}' \
'{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"subscribe_weather","arguments":{"city":"Moscow","interval_minutes":1}}}' \
| mcp-server/build/install/mcp-server/bin/mcp-server
# → "✓ Подписка активна: Moscow, сбор раз в 1 мин..."

# list_weather_subscriptions → подписка видна
# unsubscribe_weather → удалена
```

### Демо (требует сеть + LLM, запускает пользователь)
1. Сервер стартует пустым.
2. REPL: «Подпишись на погоду Москвы с интервалом 1 минута» → `subscribe_weather`.
3. Ждать ~60с → `moscow.json` растёт.
4. «Покажи подписки» → `list_weather_subscriptions`.
5. «Дай сводку» → `get_weather_summary`.
6. «Отпишись» → `unsubscribe_weather`.

## Критерии готовности

- `./gradlew :mcp-server:test` — 30 тестов green (8 переписанных scheduler + 22 без изменений).
- `./gradlew clean build` — оба модуля green, 0 регрессий.
- Raw `tools/list` = **7 tools**.
- `subscribe_weather` через raw JSON-RPC → подписка видна в `list_weather_subscriptions`.
- `grep -rn "CLI_AGENT_WEATHER" mcp-server/src/` → 0 совпадений (env убран).

## Зависимости (задачи)

R1 (scheduler v2). R2/R3 (tools + wiring — чтобы `tools/list`=7). Завершает переработку Day 18.
