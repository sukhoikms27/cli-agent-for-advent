package com.cliagent.mcp.server.notes

import com.cliagent.mcp.server.util.DataPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * JSON-независимое файловое хранилище заметок/отчётов (Day 19 — «save»-этап пайплайна): один
 * Markdown-файл на заметку в каталоге `notes/`. Atomic write (temp + `Files.move ATOMIC_MOVE
 * REPLACE_EXISTING` — AGENTS.md), slugified filename как path-injection guard (как WeatherStore Day 18).
 *
 * Потокобезопасность: tool может вызваться конкурентно в разных MCP-сессиях → `synchronized` на
 * public-методах (каталог общий). Гранулярность — весь каталог: заметки мелкие, запись редкая.
 *
 * @param dir каталог хранения (default — [DataPaths.notesDir]; переопределяется в тестах через @TempDir)
 */
internal class NotesStore(
    private val dir: Path = DataPaths.notesDir,
) {
    /**
     * Сохранить [content] в `notes/{slug(filename)}.md` (atomic write). При повторном имени —
     * перезапись. Возвращает абсолютный путь сохранённого файла (для tool-ответа).
     */
    fun save(filename: String, content: String): Path = synchronized(LOCK) {
        dir.createDirectories()
        val target = fileFor(filename)
        val tmp = target.resolveSibling(".${target.fileName}.tmp")
        // UTF-8 явно: markdown-отчёты содержат эмодзи/кириллицу; на Windows дефолтный charset ≠ UTF-8.
        tmp.writeText(content, Charsets.UTF_8)
        Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
        target
    }

    /** Список сохранённых заметок: имя файла + размер + время модификации (для list_notes tool). */
    fun list(): List<NoteEntry> = synchronized(LOCK) {
        if (!dir.exists()) return emptyList()
        dir.listDirectoryEntries("*.md")
            .map { p ->
                NoteEntry(
                    name = p.name,
                    sizeBytes = p.toFile().length(),
                    modifiedMillis = p.getLastModifiedTime().toMillis(),
                )
            }
            .sortedByDescending { it.modifiedMillis }
    }

    /** Прочитать содержимое заметки по имени файла (без расширения или с ним); null если нет. */
    fun read(filename: String): String? = synchronized(LOCK) {
        val candidates = listOf(fileFor(filename), dir.resolve(filename))
        candidates.firstOrNull { it.exists() }?.readText(Charsets.UTF_8)
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private fun fileFor(filename: String): Path =
        dir.resolve("${slugify(filename)}.md")

    /**
     * slug для имени файла: латиница/цифры/`-`, остальное → `-`. path-injection guard: `..` и
     * спецсимволы схлопываются, `../etc` → `etc.md`. Безопасно для sandboxed write (как в Day 18).
     */
    private fun slugify(filename: String): String =
        filename.lowercase().trim()
            .removeSuffix(".md")
            .replace(Regex("[^a-z0-9-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "note" }

    private companion object {
        val LOCK = Any()
    }
}

/** Метаданные сохранённой заметки (для ответа list_notes). */
internal data class NoteEntry(
    val name: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
)
