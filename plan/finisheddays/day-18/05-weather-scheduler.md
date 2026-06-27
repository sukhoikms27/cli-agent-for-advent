# Задача 05. `WeatherScheduler` — background coroutine периодического сбора

> ⚠️ **СУПЕРСЕДЕНО задачей R1.** Этот файл описывает ИСХОДНУЮ реализацию (env-driven scheduler:
> фиксированные `CLI_AGENT_WEATHER_CITIES`/`_INTERVAL_SECONDS` + общий интервал). Фактическая реализация
> — **agent-driven**: `WeatherScheduler` v2 (динамический реестр подписок), см.
> [`R1-scheduler-dynamic.md`](./R1-scheduler-dynamic.md). Обоснование переработки —
> [`R0-redesign-note.md`](./R0-redesign-note.md). Файл оставлен как историческая справка.

## Цель

Серверный планировщик: раз в N часов опрашивает погоду по списку городов и копит снапшоты в store.
Закрывает требование задания **«выполняться по расписанию»** (автоматический режим — дополнение к
on-demand `collect_weather` из задачи 04). Дает «агент 24/7» в долгоживущем http-процессе на VPS.

> По подсказке куратора («у MCP есть понятие tool»): «по расписанию» в Day 18 = on-demand tool (04)
> **+** серверный background-loop (эта задача). Оба слоя — полноценные ответы.

## Зависимости

03 (`WeatherClient.collect`), 02 (`WeatherStore.append`), 04 (общий collect-путь с `collect_weather`).
Конвенции корутин — AGENTS.md §«Корутины в CLI»: `CancellationException` не глотать, `Dispatchers.IO`.

## Файл (новый)

`weather/WeatherScheduler.kt`

## Что реализовать

```kotlin
package com.cliagent.mcp.server.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Фоновый планировщик сбора погоды (Day 18 — «выполняться по расписанию»). Раз в [intervalSeconds]
 * опрашивает [WeatherClient.collect] по каждому городу из [cities] и складывает в [WeatherStore].
 *
 * Осмыслен в долгоживущем http-процессе (VPS+systemd, Day 17-vps). В stdio-режиме (короткоживущий
 * subprocess агент-сессии) стартует, но обычно не успевает сделать ни одного сбора — инертен, не мешает.
 *
 * **Логи → stderr** (НЕ stdout): в stdio-режиме stdout несёт JSON-RPC (AGENTS.md, Day 17).
 * **CancellationException** — re-throw (никогда не глотать, AGENTS.md).
 *
 * Параметры — из env (задача 06): CLI_AGENT_WEATHER_INTERVAL_SECONDS (default 3600),
 * CLI_AGENT_WEATHER_CITIES (default "Moscow").
 */
internal class WeatherScheduler(
    private val client: WeatherClient,
    private val store: WeatherStore,
    private val cities: List<String>,
    private val intervalSeconds: Long,
) {
    private var job: Job? = null

    /** Запуск background-loop в переданном scope. Идемпотентен (повторный start — no-op). */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        if (cities.isEmpty() || intervalSeconds <= 0) {
            warn("scheduler disabled: no cities or interval=$intervalSeconds")
            return
        }
        info("scheduler started: cities=$cities interval=${intervalSeconds}s")
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    /** Корректная остановка: cancel job, await в scope. Безопасно звать из finally main()'а. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun loop() {
        try {
            while (job?.isActive == true && kotlinx.coroutines.coroutineContext[Job]!!.isActive) {
                collectAll()
                delay(intervalSeconds * 1_000L)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /** Один цикл сбора по всем городам. Ошибка одного города НЕ прерывает остальные. */
    private suspend fun collectAll() {
        for (city in cities) {
            try {
                val snap = client.collect(city)
                if (snap == null) {
                    warn("collect failed: $city (no data / network)")
                } else {
                    store.append(snap)
                    info("collected $city: ${snap.temperatureCelsius}°C, ${weatherDescription(snap.weatherCode)}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("collect error $city: ${e.message}")
            }
        }
    }

    private fun info(msg: String) = System.err.println("[weather] $msg")
    private fun warn(msg: String) = System.err.println("[weather] WARN: $msg")
}
```

## Ключевые инварианты

- **`CancellationException` — всегда re-throw** — и в `loop()`, и в `collectAll()` (внутри `for`).
  Никогда не глотать (AGENTS.md). Это позволяет корректно остановить scheduler при shutdown сервера.
- **Логи → `System.err`** — в stdio-режиме stdout = JSON-RPC (Day 17, AGENTS.md). `[weather]`-префикс
  для фильтрации.
- **Изоляция ошибок по городам** — один упавший город не валит весь цикл; остальные собираются.
  Общая сетевая авария → WARN на каждый город, retry через `intervalSeconds`.
- **Идемпотентный `start()`** — повторный вызов no-op (защита от двойного старта при wiring'е).
- **`stop()` = `job.cancel()`** — coroutine отменяется на следующем suspension-point (`delay`),
  `CancellationException` пробрасывается, `loop()` завершается. Без forced-sleep/`Thread.sleep`.
- **Empty/degenerate config → disabled** (no cities / interval≤0) — не падаем, WARN в stderr.

## Решения

- **Background coroutine, не cron/systemd-timer** — держит всё в одном процессе (VPS deployment
  Day 17-vps: systemd-unit крутит один `java -jar`). `Dispatchers.IO` — HTTP-операции (AGENTS.md).
- **`delay(intervalSeconds*1000)` ВНУТРИ loop** (после collect) — первый сбор сразу при старте, не
  через интервал. Так демо (интервал 60с) показывает данные незамедлительно.
- **Scope передаётся снаружи** (`main()` создаёт `CoroutineScope(SupervisorJob())`) — единый lifecycle
  с сервером; shutdown одного scope останавливает и сервер, и scheduler. Не порождаем orphan-scopes.
- **Без persist scheduler-состояния** — каждый цикл независим (collect→append); рестарт сервера
  начинает с新鲜 замера, история в store не теряется.

## Критерии готовности

- `./gradlew :mcp-server:build` — green.
- Unit-тест в задаче 10 (`runTest` + fake client/store): за виртуальное время scheduler делает N
  сборок → store накопил N*cities снапшотов; `stop()`/cancel корректно завершает loop.
- Ручной smoke (задача 12): http-сервер с `CLI_AGENT_WEATHER_INTERVAL_SECONDS=60
  CLI_AGENT_WEATHER_CITIES=Moscow` → в stderr каждые 60с `[weather] collected Moscow: ...`,
  файл `weather/moscow.json` растёт.

## Зависимости (задачи)

Использует 02 (store), 03 (client). Запускается в 06 (wiring `main()`). Тестируется в 10.
