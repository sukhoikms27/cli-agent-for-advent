# Задача 02. Хранилище погодных снапшотов: `WeatherSnapshot` + `WeatherStore` + `DataPaths`

## Цель

Персистентное хранилище накопленных погодных снапшотов — закрывает требование задания **«сохранять
данные»**. Один JSON-файл на город, atomic write, XDG-пути. Читается tools (задача 04) и пишется
клиентом (03) и scheduler'ом (05).

> **Почему JSON, не SQLite** (AGENTS.md): для CLI/MCP-сервера (города × снапшоты/час) JSON быстрее,
> проще, без миграций и jdbc-overhead. Меняем data class — старый JSON грузится через defaults.

## Зависимости

Задача 01 (пакет `com.cliagent.mcp.server` создан). Образцы конвенций:
- `src/main/kotlin/com/cliagent/config/AppPaths.kt` — XDG-логика (переиспользуем паттерн в mcp-модуле).
- AGENTS.md §«Файловая персистентность» — atomic write (temp + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`),
  единый `Json`-инстанс, schema evolution (поля с defaults).

## Файлы (новые)

| Файл | Содержание |
|---|---|
| `weather/WeatherSnapshot.kt` | `@Serializable` data class + WMO weather-code описание |
| `weather/WeatherStore.kt` | append / loadRange / latest + atomic write |
| `util/DataPaths.kt` | XDG-пути модуля mcp-server (weather dir) |

## Что реализовать

### `util/DataPaths.kt`
Свой object в mcp-модуле (main-модуль `AppPaths` недоступен — это другой gradle-проект). Паттерн как
в `AppPaths`, но каталог под серверные данные:
```kotlin
package com.cliagent.mcp.server.util

import java.nio.file.Path

/**
 * XDG-пути данных MCP-сервера (модуль :mcp-server). Независим от AppPaths главного приложения —
 * это отдельный gradle-проект; сервер крутится на VPS под своим юзером (systemd `User=mcp`).
 *
 * $XDG_DATA_HOME/cli-agent/weather/{city-slug}.json — накопленные снапшоты по городам.
 */
internal object DataPaths {
    val dataDir: Path = System.getenv("XDG_DATA_HOME")?.let { Path.of(it) }
        ?.resolve("cli-agent")
        ?: Path.of(System.getProperty("user.home"), ".local", "share", "cli-agent")

    /** Один JSON-файл на город: {snapshots:[WeatherSnapshot,...]}. */
    val weatherDir: Path get() = dataDir.resolve("weather")
}
```
> На VPS под systemd `User=mcp` → `~mcp/.local/share/cli-agent/weather/` (или `/var/lib/mcp/...`
> при `XDG_DATA_HOME=/var/lib/mcp` в unit-файле — опционально, Day 17-vps деплой).

### `weather/WeatherSnapshot.kt`
```kotlin
package com.cliagent.mcp.server.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Один замер погоды в момент времени (Day 18). Поля с defaults — schema evolution: старый JSON без
 * нового поля грузится бесшовно (AGENTS.md). Поля nullable там, где API может не отдать.
 *
 * @param timestamp epoch millis момента замера (для range-фильтра в summary)
 * @param city       человекочитаемое имя города (как пришло от LLM/геокодера)
 * @param lat,lon    координаты (из геокодера; фиксируют точку замера)
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
```

### `weather/WeatherStore.kt`
```kotlin
package com.cliagent.mcp.server.weather

import com.cliagent.mcp.server.util.DataPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * JSON-хранилище снапшотов погоды: один файл на город ({snapshots:[...]}). Append-only growth,
 * atomic write (temp + Files.move ATOMIC_MOVE REPLACE_EXISTING — AGENTS.md).
 *
 * Потокобезопасность: MCP-tools и background-scheduler могут писать конкурентно → `synchronized`
 * на каждом public-методе (гранулярность — файл города; JSON мал, блокировка копеечная).
 */
internal class WeatherStore(
    private val dir: Path = DataPaths.weatherDir,
    private val json: Json = AppJson,
) {
    /** Добавить снапшот в файл города (создаёт файл/каталог при необходимости). */
    fun append(snapshot: WeatherSnapshot) = synchronized(snapshot.city) {
        val file = fileFor(snapshot.city)
        file.parent.createDirectories()
        val current = readInternal(file)
        val updated = FileData(current.snapshots + snapshot)
        writeAtomic(file, updated)
    }

    /** Снапшоты города в диапазоне [fromMillis, toMillis] (включительно), по возрастанию timestamp. */
    fun loadRange(city: String, fromMillis: Long, toMillis: Long): List<WeatherSnapshot> {
        val snaps = readInternal(fileFor(city)).snapshots
        return snaps.filter { it.timestamp in fromMillis..toMillis }.sortedBy { it.timestamp }
    }

    /** Последний снапшот города или null, если данных нет. */
    fun latest(city: String): WeatherSnapshot? = readInternal(fileFor(city)).snapshots.maxByOrNull { it.timestamp }

    // ── internals ──────────────────────────────────────────────────────────────

    private fun fileFor(city: String): Path = dir.resolve("${slugify(city)}.json")

    private fun readInternal(file: Path): FileData {
        if (!file.exists()) return FileData(emptyList())
        val data = runCatching { json.decodeFromString<FileData>(file.readText()) }.getOrNull()
            ?: return FileData(emptyList()) // битый/пустой файл → начинаем с чистого (не падаем)
        return data
    }

    /** Atomic write: temp-файл рядом + rename. Прерывание на любом шаге не калечит рабочий файл. */
    private fun writeAtomic(target: Path, data: FileData) {
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        tmp.writeText(json.encodeToString(FileData.serializer(), data))
        Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }

    /** slug для имени файла: латиница/цифры/`-`, остальное → `-`. Защита от path-injection. */
    private fun slugify(city: String): String =
        city.lowercase().trim()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "unknown" }

    @Serializable
    private data class FileData(val snapshots: List<WeatherSnapshot> = emptyList())
}

/** Единый Json-инстанс модуля (AGENTS.md): forward-compat + compact + coerce. */
internal val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}
```

## Ключевые инварианты

- **Atomic write** (temp + `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`) — прерывание процесса в
  середине записи не ломает рабочий файл (AGENTS.md).
- **Schema evolution:** все поля `WeatherSnapshot` имеют defaults → добавление поля в будущем не
  ломает старые JSON; удаление поля не делается (AGENTS.md).
- **Единый `AppJson`** с `ignoreUnknownKeys`/`coerceInputValues` — forward/backward compat.
- **`slugify` для filename** — отдельная sanitization (имя города от LLM ≠ безопасный filename);
  allowlist `[^a-z0-9-]+`→`-`. **Никогда** не подставлять `city` в путь напрямую.
- **Конкурентный доступ** (`synchronized(city)`) — tools и scheduler пишут; read-методы тоже под
  локом для консистентности с atomic write.
- **Битый/пустой файл → `FileData(emptyList())`** — сервер не падает; сбор начинается с нуля.

## Решения

- **Один файл на город** (а не один общий) — изоляция, простой range-фильтр, atomic write не лочит
  все города сразу.
- **`timestamp` epoch millis** — нативный Long для range-запросов; форматирование в текст только в
  tool-ответе (задача 04), не в storage.
- **`@SerialName` snake_case** в JSON — человекочитаемость файла (debug-удобство), PascalCase в Kotlin
  (конвенция). Тот же приём, что у `GitHubRepo` (Day 17).
- **`source` поле** — закладка под сравнение источников в будущем (Day 19+) и аудит.
- **Не храним «чистую» историю в RAM** — только файл; RAM-кеш не нужен (файлы малы, reads редки).

## Критерии готовности

- `./gradlew :mcp-server:build` — green (компилируется).
- Ручная smoke-проверка (или unit-тест в задаче 08): `append(s1); append(s2); loadRange(...)` → `[s1, s2]`,
  `latest()` → `s2`, файл `$XDG_DATA_HOME/cli-agent/weather/<slug>.json` создан и валиден.
- `slugify("Moscow")` → `moscow`; `slugify("Saint Petersburg")` → `saint-petersburg`;
  `slugify("../etc")` → `etc` (нет path-эскейпа).

## Зависимости (задачи)

Используется в 03 (client пишет snapshot), 04 (tools читают range/latest), 05 (scheduler пишет).
Тестируется в 08.
