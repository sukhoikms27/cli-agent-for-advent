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
 * `get_weather_summary` (pull-модель, см. `R0-redesign-note`).
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
    /** Внутреннее состояние подписки: город, интервал, фоновый job. */
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

    /** Результат subscribe(): интервал после coerce (для человекочитаемого ответа tool'а). */
    data class SubscribeResult(val city: String, val intervalMinutes: Long)

    private val subscriptions = mutableMapOf<String, Subscription>()
    private val lock = Any()

    /**
     * Зарегистрировать/обновить подписку. Повторный subscribe для того же города → cancel старого
     * job + запуск нового (обновление интервала). Возвращает [SubscribeResult] с coerced интервалом.
     *
     * `intervalMinutes` валидируется и coerce'ится в [MIN_INTERVAL, MAX_INTERVAL].
     */
    fun subscribe(city: String, intervalMinutes: Long): SubscribeResult {
        val interval = intervalMinutes.coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        synchronized(lock) {
            // Если уже есть подписка на этот город — cancel её job (рестарт под новый интервал).
            subscriptions.remove(city)?.job?.cancel()
            val job = scope.launch(dispatcher) { collectLoop(city, interval) }
            subscriptions[city] = Subscription(city, interval, job)
        }
        info("subscribed $city: every ${interval}m (delay-first)")
        return SubscribeResult(city, interval)
    }

    /** Остановить подписку на город. true — была подписка и удалена; false — подписки не было. */
    fun unsubscribe(city: String): Boolean = synchronized(lock) {
        val sub = subscriptions.remove(city)
        sub?.job?.cancel()
        if (sub != null) info("unsubscribed $city")
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
