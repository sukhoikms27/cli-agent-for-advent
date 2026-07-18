package com.cliagent.agent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 34 — unit-тесты [FileToolExecutor] и [resolveSafe]. Создаёт sandbox в @TempDir и проверяет
 * read-only tools, write (с confirm-callback), path-traversal guard.
 */
class FileToolExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun executor(confirmWrite: (suspend (String, String) -> Boolean)? = null): FileToolExecutor {
        val root = tempDir.toFile()
        // Создаём структуру для тестов.
        java.io.File(root, "src").mkdirs()
        java.io.File(root, "src/Main.kt").writeText("fun main() {\n    println(\"hello\")\n}\n")
        java.io.File(root, "README.md").writeText("# Project\n\nTODO: add tests\n")
        java.io.File(root, "build").mkdirs()
        java.io.File(root, "build/generated.kt").writeText("// should be skipped")
        return FileToolExecutor(root = root, confirmWrite = confirmWrite)
    }

    // ── resolveSafe (path traversal guard) ─────────────────────────────────────

    @Test
    fun `resolveSafe accepts relative path inside root`() {
        val root = tempDir.toFile()
        val resolved = resolveSafe(root, "src/Main.kt")
        assertNotNull(resolved)
        assertTrue(resolved!!.path.startsWith(root.canonicalPath))
    }

    @Test
    fun `resolveSafe rejects parent directory traversal`() {
        val root = tempDir.toFile()
        val resolved = resolveSafe(root, "../../etc/passwd")
        assertEquals(null, resolved)
    }

    @Test
    fun `resolveSafe rejects absolute path outside root`() {
        val root = tempDir.toFile()
        val resolved = resolveSafe(root, "/etc/passwd")
        assertEquals(null, resolved)
    }

    @Test
    fun `resolveSafe accepts nested path`() {
        val root = tempDir.toFile()
        java.io.File(root, "a/b").mkdirs()
        val resolved = resolveSafe(root, "a/b/c.txt")
        assertNotNull(resolved)
    }

    // ── read_file ──────────────────────────────────────────────────────────────

    @Test
    fun `read_file returns content for existing file`() = runTest {
        val exec = executor()
        val result = exec.call("read_file", mapOf("path" to "src/Main.kt"))
        assertTrue(result.contains("fun main()"))
        assertTrue(result.contains("println"))
    }

    @Test
    fun `read_file returns error for missing file`() = runTest {
        val exec = executor()
        val result = exec.call("read_file", mapOf("path" to "nonexistent.kt"))
        assertTrue(result.contains("не найден") || result.contains("Error"))
    }

    @Test
    fun `read_file blocks path traversal`() = runTest {
        val exec = executor()
        val result = exec.call("read_file", mapOf("path" to "../../../etc/passwd"))
        assertTrue(result.contains("path traversal") || result.contains("sandbox"))
    }

    // ── find_in_files ──────────────────────────────────────────────────────────

    @Test
    fun `find_in_files locates matches case insensitive`() = runTest {
        val exec = executor()
        val result = exec.call("find_in_files", mapOf("query" to "println"))
        assertTrue(result.contains("Main.kt:"))
    }

    @Test
    fun `find_in_files locates TODO across files`() = runTest {
        val exec = executor()
        val result = exec.call("find_in_files", mapOf("query" to "TODO"))
        assertTrue(result.contains("README.md:"))
    }

    @Test
    fun `find_in_files skips build directory`() = runTest {
        val exec = executor()
        val result = exec.call("find_in_files", mapOf("query" to "generated"))
        assertTrue(result.contains("не найдено") || result.contains("no matches") || result.isEmpty())
    }

    @Test
    fun `find_in_files respects extension filter`() = runTest {
        val exec = executor()
        // query "project" — есть только в README.md, не в .kt
        val result = exec.call("find_in_files", mapOf("query" to "project", "extension" to "kt"))
        assertTrue(result.contains("не найдено") || result.isEmpty())
    }

    @Test
    fun `find_in_files returns no matches for unknown query`() = runTest {
        val exec = executor()
        val result = exec.call("find_in_files", mapOf("query" to "ZZNONEXISTENTZZ"))
        assertTrue(result.contains("не найдено") || result.contains("no matches"))
    }

    // ── list_project_files ─────────────────────────────────────────────────────

    @Test
    fun `list_project_files returns relative paths`() = runTest {
        val exec = executor()
        val result = exec.call("list_project_files", mapOf())
        assertTrue(result.contains("src/Main.kt"))
        assertTrue(result.contains("README.md"))
    }

    @Test
    fun `list_project_files skips build directory`() = runTest {
        val exec = executor()
        val result = exec.call("list_project_files", mapOf())
        assertFalse(result.contains("build/generated.kt"))
    }

    @Test
    fun `list_project_files extension filter works`() = runTest {
        val exec = executor()
        val result = exec.call("list_project_files", mapOf("extension" to "md"))
        assertTrue(result.contains("README.md"))
        assertFalse(result.contains("Main.kt"))
    }

    // ── write_file (DANGEROUS) ─────────────────────────────────────────────────

    @Test
    fun `write_file blocked without confirmWrite callback`() = runTest {
        val exec = executor(confirmWrite = null)   // read-only режим
        val result = exec.call("write_file", mapOf("path" to "new.txt", "content" to "data"))
        assertTrue(result.contains("заблокирован") || result.contains("read-only"))
        // Файл НЕ создан.
        assertFalse(java.io.File(tempDir.toFile(), "new.txt").exists())
    }

    @Test
    fun `write_file approved writes content`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> true })   // авто-подтверждение
        val result = exec.call("write_file", mapOf("path" to "created.txt", "content" to "hello"))
        assertTrue(result.contains("Записан") || result.contains("✓"))
        val file = java.io.File(tempDir.toFile(), "created.txt")
        assertTrue(file.exists())
        assertEquals("hello", file.readText())
    }

    @Test
    fun `write_file rejected does NOT write`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> false })   // отклонение
        val result = exec.call("write_file", mapOf("path" to "rejected.txt", "content" to "x"))
        assertTrue(result.contains("отменена") || result.contains("отклонён") || result.contains("отклонено"))
        assertFalse(java.io.File(tempDir.toFile(), "rejected.txt").exists())
    }

    @Test
    fun `write_file blocks path traversal even with confirm`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> true })
        val result = exec.call("write_file", mapOf("path" to "../../evil.txt", "content" to "x"))
        assertTrue(result.contains("path traversal") || result.contains("sandbox"))
        assertFalse(java.io.File(tempDir.toFile().parentFile, "evil.txt").exists())
    }

    // ── definitions ────────────────────────────────────────────────────────────

    @Test
    fun `definitions exposes all four tools`() = runTest {
        val exec = executor()
        val defs = exec.definitions()
        val names = defs.map { it.function.name }
        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("find_in_files"))
        assertTrue(names.contains("list_project_files"))
        assertTrue(names.contains("write_file"))
    }
}
