package com.cliagent.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    // ── delete_file (DANGEROUS, irreversible) ──────────────────────────────────
    // Регрессия: баг "delete_file(no args)" + баг "gate отклонил — Неизвестная операция".
    // delete_file проходит через тот же gate, что write_file: RejectAllGate → блокировка,
    // approve=true → удаление, approve=false → отказ без удаления.

    @Test
    fun `delete_file blocked without confirmWrite callback`() = runTest {
        val exec = executor(confirmWrite = null)   // read-only gate
        // Сначала создаём файл, который будем пытаться удалить.
        java.io.File(tempDir.toFile(), "victim.txt").writeText("data")
        val result = exec.call("delete_file", mapOf("path" to "victim.txt"))
        assertTrue(result.contains("заблокирован") || result.contains("read-only"),
            "expected block message, got: $result")
        assertTrue(java.io.File(tempDir.toFile(), "victim.txt").exists(),
            "file must NOT be deleted when gate rejects")
    }

    @Test
    fun `delete_file approved removes file`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> true })   // approve all
        val victim = java.io.File(tempDir.toFile(), "to-delete.txt").apply { writeText("bye") }
        assertTrue(victim.exists())
        val result = exec.call("delete_file", mapOf("path" to "to-delete.txt"))
        assertTrue(result.contains("Удалён") || result.contains("✓"),
            "expected success message, got: $result")
        assertFalse(victim.exists(), "file must be deleted after approved gate")
    }

    @Test
    fun `delete_file rejected does NOT remove file`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> false })   // reject all
        val victim = java.io.File(tempDir.toFile(), "protected.txt").apply { writeText("keep me") }
        val result = exec.call("delete_file", mapOf("path" to "protected.txt"))
        assertTrue(result.contains("отменена") || result.contains("отклонён") || result.contains("отклонено") || result.contains("заблокирован"),
            "expected rejection message, got: $result")
        assertTrue(victim.exists(), "file must survive when gate rejects")
    }

    @Test
    fun `delete_file blocks path traversal even with confirm`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> true })
        val result = exec.call("delete_file", mapOf("path" to "../../etc/passwd"))
        assertTrue(result.contains("path traversal") || result.contains("sandbox"))
    }

    @Test
    fun `delete_file returns error for missing file`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> true })
        val result = exec.call("delete_file", mapOf("path" to "never-existed.txt"))
        assertTrue(result.contains("не найден") || result.contains("Error"))
    }

    @Test
    fun `delete_file recursively removes directory`() = runTest {
        val exec = executor(confirmWrite = { _, _ -> true })
        val dir = java.io.File(tempDir.toFile(), "to-remove-dir").apply {
            mkdirs()
            java.io.File(this, "inner.txt").writeText("x")
        }
        assertTrue(dir.exists())
        val result = exec.call("delete_file", mapOf("path" to "to-remove-dir"))
        assertTrue(result.contains("Удалён") || result.contains("✓"))
        assertFalse(dir.exists(), "directory must be removed recursively")
    }

    @Test
    fun `delete_file works via DangerousOpGate interface`() = runTest {
        // Прямое использование gate-интерфейса (а не legacy confirmWrite-callback).
        val root = tempDir.toFile()
        java.io.File(root, "src").mkdirs()  // structure like executor()
        val exec = FileToolExecutor(root = root, gate = AutoApproveGate())
        val victim = java.io.File(root, "via-gate.txt").apply { writeText("data") }
        val result = exec.call("delete_file", mapOf("path" to "via-gate.txt"))
        assertTrue(result.contains("Удалён") || result.contains("✓"))
        assertFalse(victim.exists())
    }

    // ── definitions ────────────────────────────────────────────────────────────

    @Test
    fun `definitions exposes all five tools`() = runTest {
        val exec = executor()
        val defs = exec.definitions()
        val names = defs.map { it.function.name }
        assertTrue(names.contains("read_file"))
        assertTrue(names.contains("find_in_files"))
        assertTrue(names.contains("list_project_files"))
        assertTrue(names.contains("write_file"))
        assertTrue(names.contains("delete_file"))
    }

    // ── JSON Schema shape (regression: delete_file(no args) bug) ───────────────
    // День 34: z.ai (Schema 1210) отклоняет tool-definitions, где parameters не имеет
    // обёртки {type:"object", properties:{...}, required:[...]}. Без неё LLM не понимает,
    // какие аргументы нужно передать, и вызывает tool с пустыми args → "параметр обязателен".
    // Этот тест фиксирует контракт: каждое definition обязано иметь type:object + properties.

    @Test
    fun `every tool schema has type=object and properties wrapper`() = runTest {
        val exec = executor()
        for (def in exec.definitions()) {
            val params = def.function.parameters
            assertNotNull(params, "${def.function.name}: parameters must not be null")
            val obj = params as? JsonObject
                ?: error("${def.function.name}: parameters must be JsonObject, got ${params?.let { it::class.simpleName }}")
            assertEquals("object", obj["type"]?.jsonPrimitive?.contentOrNull,
                "${def.function.name}: schema.type must be \"object\"")
            assertNotNull(obj["properties"], "${def.function.name}: schema must have 'properties'")
            assertTrue(obj["properties"] is JsonObject,
                "${def.function.name}: 'properties' must be JsonObject")
        }
    }

    @Test
    fun `required params are declared for dangerous tools`() = runTest {
        val exec = executor()
        val byName = exec.definitions().associateBy { it.function.name }
        // write_file требует path + content; delete_file требует path.
        val writeRequired = (byName.getValue("write_file").function.parameters as JsonObject)
            .getRequiredStrings("required")
        val deleteRequired = (byName.getValue("delete_file").function.parameters as JsonObject)
            .getRequiredStrings("required")
        assertEquals(setOf("path", "content"), writeRequired.toSet())
        assertEquals(setOf("path"), deleteRequired.toSet())
    }

    private fun JsonObject.getRequiredStrings(key: String): List<String> {
        val arr = this[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

}
