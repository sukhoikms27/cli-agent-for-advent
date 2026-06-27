# Задача R1. `WeatherScheduler` v2 — динамический реестр подписок

## Цель

Перепроектировать `WeatherScheduler` (задача 05) из «фиксированный cities+interval из env» в
**динамический реестр подписок**, управляемый агентом через tools (R2). Per-city background-jobs,
интервал per-city, подписки в памяти, первый замер через интервал (delay-first).

> Обоснование redesign'а — в `R0-redesign-note.md`. Это переработка задачи 05.

## Зависимости

02 (`WeatherStore.append`), 03 (`WeatherClient.collect`). Текущая реализация задачи 05
(`mcp-server/.../weather/WeatherScheduler.kt`) — заменяется целиком.

## Файл

`weather/WeatherScheduler.kt` — полная перезапись.

## Что реализовать

```kotlin
package com.cliagent.mcp.server.weather

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Динамический реестр подписок на периодический сбор погоды (Day 18 redesign — agent-driven).
 *
 * Агент через MCP-tools (subscribe_weather/unsubscribe_weather) управляет расписанием: сервер
 * регистрирует **per-city background-job**, который каждые [intervalMinutes] минут собирает погоду и
 * складывает в [WeatherStore]. Jobs крутятся 24/7 независимо от запросов; результат забирается
 * `get_weather_summary` (pull-модель, см. R0-redesign-note).
 *
 * **Подписки в памяти**: рестарт сервера → пусто, агент перерегистрирует (решение пользователя).
 *
 * **Delay-first**: per-city job = `delay → collect → delay → …`. Первый замер — через интервал
 * после подписки, не сразу (решение пользователя «ждать интервал»).
 *
 * **`dispatcher`** инъектируется (default `Dispatchers.IO`); тесты (R5) подставляют тестовый для
 * контроля виртуальным временем.
 *
 * @param scope жизненный цикл всех per-city jobs (SupervisorJob в McpServerApp.main)
 */
internal class WeatherScheduler(
    private val client: WeatherClient,
    private val store: WeatherStore,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Subscription(
        val city: String,
        val intervalMinutes: Long,
        val job: Job,
    )

    /** Информация о подписке для list()-ответа tool'а. */
    data class SubscriptionInfo(
        val city: String,
        val intervalMinutes: Long,
        val active: Boolean,
    )

    /** Результат subscribe(): создана новая или обновлена (рестарт job). */
    data class SubscribeResult(val city: String, val intervalMinutes: Long, val created: Boolean)

    private val subscriptions = mutableMapOf<String, Subscription>()
    private val lock = Any()

    /**
     * Зарегистрировать/обновить подписку. Повторный subscribe для того же города → cancel старого
     * job + запуск нового (обновление интервала). Возвращает [SubscribeResult].
     *
     * `intervalMinutes` валидируется и coerce'ится в [MIN_INTERVAL, MAX_INTERVAL].
     */
    fun subscribe(city: String, intervalMinutes: Long): SubscribeResult {
        val interval = intervalMinutes.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        return synchronized(lock) {
            // Если уже есть подписка на этот город — cancel её job (рестарт под новый интервал).
            subscriptions.remove(city)?.job?.cancel()
            val job = scope.launch(dispatcher) { collectLoop(city, interval) }
            subscriptions[city] = Subscription(city, interval, job)
            SubscribeResult(city, interval, created = true)
        }
    }

    /** Остановить подписку на город. true — была подписка и удалена; false — подписки не было. */
    fun unsubscribe(city: String): Boolean = synchronized(lock) {
        val sub = subscriptions.remove(city)
        sub?.job?.cancel()
        sub != null
    }

    /** Снимок активных подписок для ответа list_weather_subscriptions. */
    fun list(): List<SubscriptionInfo> = synchronized(lock) {
        subscriptions.values.map { SubscriptionInfo(it.city, it.intervalMinutes, it.job.isActive) }
    }

    /** Shutdown: отменить ВСЕ jobs. Безопасно звать из finally main()'а; idempotent. */
    fun stopAll() = synchronized(lock) {
        subscriptions.values.forEach { it.job.cancel() }
        subscriptions.clear()
    }

    // ── internals ──────────────────────────────────────────────────────────────

    /** Per-city loop: delay → collect → delay → … (delay-first по решению пользователя). */
    private suspend fun collectLoop(city: String, intervalMinutes: Long) {
        try {
            while (currentCoroutineContext().isActive) {
                delay(intervalMinutes * 60_000L)
                collectOne(city)
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    /** Один сбор города: geocode+forecast → store.append. Ошибка → WARN, не валит job. */
    private suspend fun collectOne(city: String) {
        try {
            val snap = client.collect(city)
            if (snap == null) {
                warn("collect failed: $city (no data / network)")
            } else {
                store.append(snap)
                info("collected $city: ${snap.temperatureCelsius.toInt()}°C, ${weatherDescription(snap.weatherCode)}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("collect error $city: ${e.message}")
        }
    }

    private fun info(msg: String) = System.err.println("[weather] $msg")
    private fun warn(msg: String) = System.err.println("[weather] WARN: $msg")

    private companion object {
        // Интервал: от минуты до недели. Coerce защищает от 0/негатива/абсурда от LLM.
        const val MIN_INTERVAL = 1L
        const val MAX_INTERVAL = 10_080L // 7 дней
    }
}
```

## Ключевые инварианты

- **Delay-first**: `collectLoop` делает `delay` ПЕРВЫМ. Подписка на интервал 60с → первый сбор через
  ~60с, не сразу (решение пользователя). Тест R5: за виртуальное время < interval — 0 замеров.
- **Per-city jobs**: каждый город — своя корутина со своим интервалом. Изоляция: ошибка одного города
  не влияет на другие. Повторный `subscribe(city)` → cancel старого job + новый (обновление интервала).
- **`synchronized(lock)`** на всех public-методах — конкурентный доступ из tool-handlers (несколько
  http-сессий) и per-city jobs (`collectOne` пишет в shared store, но store сам thread-safe; реестр
  — под локом).
- **`CancellationException` — всегда re-throw** (в `collectLoop` и `collectOne`): корректная отмена
  job при `unsubscribe`/`stopAll` (AGENTS.md).
- **`dispatcher` inject**: prod → `Dispatchers.IO` (HTTP-операции), тесты → `StandardTestDispatcher`
  для контроля виртуальным временем (как в задаче 10/05).
- **interval coerce `[1, 10080]`** — защита от абсурда от LLM (0, негатив, 100000).
- **Логи → stderr** (`[weather]`-префикс) — stdio-режим: stdout = JSON-RPC (Day 17, AGENTS.md).

## Решения

- **`Subscription`/`SubscriptionInfo`/`SubscribeResult` — `data class`** — компактные DTO для внутреннего
  состояния и tool-ответов. `SubscriptionInfo`/`SubscribeResult` public-видимы для `WeatherTools.kt` (R2).
- **`stopAll()` + `unsubscribe()` idempotent** — повторный вызов не падает (cancel уже отменённого job — no-op).
- **Без автоподписок при создании**: scheduler стартует пустым; заполнение — через tools (R2/R3).
  Контрастирует со старой задачей 05 (env-список при старте).

## Критерии готовности

- `./gradlew :mcp-server:compileKotlin` — green.
- `WeatherSchedulerTest` (R5) green: subscribe→list(1)→unsubscribe→list(0); per-city интервал;
  повторный subscribe рестартит job (старый cancelled); delay-first (за время < interval — 0 замеров,
  > interval — 1 замер); изоляция ошибок.

## Зависимости (задачи)

Использует 02 (store), 03 (client). Вызывается из R2 (tools), R3 (wiring). Тест — R5.
