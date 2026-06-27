package com.cliagent.mcp.server.notes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 19: NotesStore (save-этап пайплайна). Паттерн Day 18 WeatherStoreTest: @TempDir + прямые
 * проверки atomic write, slugify и path-injection guard.
 */
class NotesStoreTest {

    @Test
    fun `save creates md file with content`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        val path = store.save("kotlin-digest", "# Kotlin\nтекст отчёта")
        assertEquals("kotlin-digest.md", path.fileName.toString())
        assertEquals("# Kotlin\nтекст отчёта", path.toFile().readText())
    }

    @Test
    fun `save appends md extension only once`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        val path = store.save("report.md", "контент")
        assertEquals("report.md", path.fileName.toString())
        // не должно стать report.md.md
        assertTrue(!dir.resolve("report.md.md").toFile().exists())
    }

    @Test
    fun `save overwrites existing file with same name`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        store.save("note", "версия 1")
        store.save("note", "версия 2")
        val content = dir.resolve("note.md").toFile().readText()
        assertEquals("версия 2", content)
    }

    @Test
    fun `slugify sanitizes spaces and special chars`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        store.save("Kotlin Digest 2026!", "x")
        assertTrue(dir.resolve("kotlin-digest-2026.md").toFile().exists())
    }

    @Test
    fun `path-injection attempt collapses to safe filename`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        // ../etc/passwd должен стать безопасным именем, не выйти за notes/
        store.save("../etc/passwd", "x")
        // ничего вне dir создано не было
        assertTrue(dir.parent.resolve("etc").toFile().exists().not())
        assertTrue(dir.resolve("etc-passwd.md").toFile().exists())
    }

    @Test
    fun `blank filename falls back to note`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        store.save("   ", "контент")
        assertTrue(dir.resolve("note.md").toFile().exists())
    }

    @Test
    fun `list returns saved notes sorted by modification desc`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        store.save("first", "a")
        Thread.sleep(15) // разделить mtime
        store.save("second", "bb")
        val list = store.list()
        assertEquals(2, list.size)
        assertEquals("second.md", list[0].name) // новее — первым
        assertEquals("first.md", list[1].name)
        assertEquals(2L, list.first { it.name == "second.md" }.sizeBytes)
    }

    @Test
    fun `list returns empty when dir does not exist yet`(@TempDir dir: Path) {
        val store = NotesStore(dir.resolve("nonexistent"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `read returns content by name with or without extension`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        store.save("report", "тело отчёта")
        assertEquals("тело отчёта", store.read("report"))
        assertEquals("тело отчёта", store.read("report.md"))
    }

    @Test
    fun `read returns null for missing note`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        assertNull(store.read("nope"))
    }

    @Test
    fun `cyrillic filename slugified to dashes not dropped`(@TempDir dir: Path) {
        val store = NotesStore(dir)
        store.save("Отчёт Питер", "x")
        // кириллица схлопывается в дефисы; файл создаётся, не теряется
        val files = dir.toFile().listFiles { _, n -> n.endsWith(".md") } ?: emptyArray()
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.name.matches(Regex("[a-z0-9-]+\\.md")) })
    }
}
