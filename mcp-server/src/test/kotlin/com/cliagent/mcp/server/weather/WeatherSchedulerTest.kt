package com.cliagent.mcp.server.weather

import io.mockk.coEvery
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
        runCurrent() // дать корутине подписки стартануть
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
        runCurrent()
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

        scheduler.subscribe("Moscow", intervalMinutes = 60) // час
        scheduler.subscribe("Kazan", intervalMinutes = 30) // полчаса
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
        runCurrent()
        assertEquals(1, scheduler.list().size) // одна подписка (замена, не дубль)
        assertEquals(15L, scheduler.list().first().intervalMinutes)
        scheduler.stopAll()
    }

    @Test
    fun `error in one city does not affect others`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = mockk<WeatherClient>()
        coEvery { client.collect("Moscow") } returns null // имитация сбоя
        coEvery { client.collect("Kazan") } returns snap("Kazan", 2.0)
        val store = WeatherStore(tempDir())
        val scheduler = makeScheduler(client, store, dispatcher)

        scheduler.subscribe("Moscow", intervalMinutes = 60)
        scheduler.subscribe("Kazan", intervalMinutes = 60)
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(0, store.loadRange("Moscow", 0, Long.MAX_VALUE).size) // Moscow падал, не записан
        assertEquals(1, store.loadRange("Kazan", 0, Long.MAX_VALUE).size) // Kazan собран
        assertTrue(scheduler.list().any { it.city == "Moscow" }) // подписка Moscow жива (job не убит)
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
        runCurrent()
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
