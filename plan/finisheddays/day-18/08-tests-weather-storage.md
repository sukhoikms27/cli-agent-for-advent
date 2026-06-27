# Задача 08. Тесты `WeatherStore`

## Цель

Покрыть `WeatherStore` (append/loadRange/latest, atomic write, slugify, schema evolution) юнит-тестами
с временной директорией. Без mock'ов — хранилище работает с реальной FS (tmp dir).

## Зависимости

02 (`WeatherStore`, `WeatherSnapshot`). 07 (test-инфраструктура).

## Тест-класс (новый)

`mcp-server/src/test/kotlin/com/cliagent/mcp/server/weather/WeatherStoreTest.kt`

## Что реализовать

```kotlin
package com.cliagent.mcp.server.weather

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class WeatherStoreTest {

    private val now = 1_700_000_000_000L
    private fun snap(ts: Long, city: String = "Moscow", temp: Double = 20.0) = WeatherSnapshot(
        timestamp = ts, city = city, temperatureCelsius = temp,
    )

    @Test
    fun `append then latest returns last snapshot`(@TempDir dir: Path) {
        val store = WeatherStore(dir)
        store.append(snap(now, temp = 10.0))
        store.append(snap(now + 60_000, temp = 15.0))
        val latest = store.latest("Moscow")
        assertEquals(15.0, latest?.temperatureCelsius)
    }

    @Test
    fun `latest returns null for unknown city`(@TempDir dir: Path) {
        val store = WeatherStore(dir)
        assertNull(store.latest("Nowhere"))
    }

    @Test
    fun `loadRange filters by time window`(@TempDir dir: Path) {
        val store = WeatherStore(dir)
        store.append(snap(now))
        store.append(snap(now + 3_600_000))            // +1h
        store.append(snap(now + 7_200_000))            // +2h
        val range = store.loadRange("Moscow", now + 1_000, now + 3_600_000) // ~1h window
        assertEquals(1, range.size)
        assertEquals(now + 3_600_000, range[0].timestamp)
    }

    @Test
    fun `loadRange sorted ascending by timestamp`(@TempDir dir: Path) {
        val store = WeatherStore(dir)
        store.append(snap(now + 7_200_000))
        store.append(snap(now))
        store.append(snap(now + 3_600_000))
        val range = store.loadRange("Moscow", 0, Long.MAX_VALUE)
        assertEquals(listOf(now, now + 3_600_000, now + 7_200_000), range.map { it.timestamp })
    }

    @Test
    fun `per-city isolation — separate files`(@TempDir dir: Path) {
        val store = WeatherStore(dir)
        store.append(snap(now, city = "Moscow"))
        store.append(snap(now, city = "Saint Petersburg"))
        assertEquals(1, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
        assertEquals(1, store.loadRange("Saint Petersburg", 0, Long.MAX_VALUE).size)
        assertNull(store.latest("Kazan"))
    }

    @Test
    fun `slugify sanitizes city for filename`(@TempDir dir: Path) {
        val store = WeatherStore(dir)
        store.append(snap(now, city = "Saint Petersburg"))
        assertTrue(dir.resolve("saint-petersburg.json").toFile().exists(),
            "multi-word city должен стать saint-petersburg.json")
    }

    @Test
    fun `schema evolution — extra unknown field in JSON does not break load`(@TempDir dir: Path) {
        val file = dir.resolve("moscow.json")
        file.toFile().writeText("""{"snapshots":[{"timestamp":$now,"city":"Moscow","temperature_celsius":5.0,"FUTURE_FIELD":"x"}]}""")
        val store = WeatherStore(dir)
        val latest = store.latest("Moscow")
        assertEquals(5.0, latest?.temperatureCelsius)
    }

    @Test
    fun `corrupted file treated as empty — does not throw`(@TempDir dir: Path) {
        dir.resolve("moscow.json").toFile().writeText("{not valid json")
        val store = WeatherStore(dir)
        assertNull(store.latest("Moscow"))
        store.append(snap(now))                          // append после «битого» файла работает
        assertEquals(1, store.loadRange("Moscow", 0, Long.MAX_VALUE).size)
    }
}
```

## Что проверяет

| # | Кейс | Ожидание |
|---|---|---|
| 1 | append×2 → latest | последний по timestamp |
| 2 | unknown city → latest | `null` |
| 3 | loadRange с окном | только снапшоты в `[from,to]` |
| 4 | loadRange сортировка | ascending timestamp (даже если писались вразнобой) |
| 5 | изоляция по городам | разные файлы, города не смешиваются |
| 6 | slugify multi-word | `saint-petersburg.json` |
| 7 | schema evolution | неизвестное поле не ломает load (`ignoreUnknownKeys`) |
| 8 | битый файл | не падает, трактуется как empty; последующий append работает |

## Ключевые инварианты

- **`@TempDir`** (JUnit 5) — уникальная временная директория на тест, авто-cleanup. Не загрязняем
  реальную FS / `XDG_DATA_HOME`.
- **`WeatherStore(dir)`** — конструктор принимает кастомный `dir` (создан в задаче 02 именно для
  тестируемости); default — `DataPaths.weatherDir`.
- **schema-evolution тест** — прямой JSON-poke (пишем `FUTURE_FIELD`), проверяет forward-compat
  (AGENTS.md): добавление поля в будущем не ломает старых читателей.
- **битый-файл тест** — подтверждает инвариант `readInternal` (задача 02): corruption → empty, не crash.

## Критерии готовности

- `./gradlew :mcp-server:test --tests "*WeatherStoreTest"` — green (8 тестов).
- `./gradlew :mcp-server:test` — 0 регрессий (других тестов в модуле пока нет, кроме 09–11).

## Зависимости (задачи)

02 (store). 07 (infra). Независимо от 09/10/11.
