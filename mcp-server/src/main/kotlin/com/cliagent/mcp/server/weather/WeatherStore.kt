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
 * JSON-хранилище снапшотов погоды (Day 18 — «сохранять данные»): один файл на город
 * (`{snapshots:[...]}`). Append-only growth, atomic write (temp + `Files.move ATOMIC_MOVE
 * REPLACE_EXISTING` — AGENTS.md).
 *
 * Потокобезопасность: MCP-tools и background-scheduler могут писать конкурентно → `synchronized`
 * на каждом public-методе (гранулярность — город; JSON мал, блокировка копеечная).
 *
 * @param dir каталог хранения (default — [DataPaths.weatherDir]; переопределяется в тестах)
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
    fun latest(city: String): WeatherSnapshot? =
        readInternal(fileFor(city)).snapshots.maxByOrNull { it.timestamp }

    // ── internals ──────────────────────────────────────────────────────────────

    private fun fileFor(city: String): Path = dir.resolve("${slugify(city)}.json")

    private fun readInternal(file: Path): FileData {
        if (!file.exists()) return FileData(emptyList())
        // Битый/пустой файл → начинаем с чистого (не падаем; сбор продолжится).
        // UTF-8 явно: city-name и описания Open-Meteo содержат не-ASCII (Москва, Göttingen).
        return runCatching { json.decodeFromString<FileData>(file.readText(Charsets.UTF_8)) }
            .getOrDefault(FileData(emptyList()))
    }

    /** Atomic write: temp-файл рядом + rename. Прерывание на любом шаге не калечит рабочий файл. */
    private fun writeAtomic(target: Path, data: FileData) {
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        tmp.writeText(json.encodeToString(FileData.serializer(), data), Charsets.UTF_8)
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
