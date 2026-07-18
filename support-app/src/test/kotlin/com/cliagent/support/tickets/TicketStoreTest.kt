package com.cliagent.support.tickets

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 33 — unit-тесты [TicketStore]. Использует @TempDir для изоляции (как NotesStoreTest в mcp-server).
 * Проверяет CRUD-операции, поиск, atomic write, graceful на пустом/битом файле.
 */
class TicketStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun store(): TicketStore = TicketStore(file = tempDir.resolve("tickets.json"))

    private fun sampleTicket(id: Int = 1, email: String = "user@example.com") = Ticket(
        id = id,
        subject = "Проблема $id",
        status = "open",
        priority = "medium",
        customerEmail = email,
        description = "Описание проблемы $id",
    )

    @Test
    fun `all returns empty list when file does not exist`() = runTest {
        val s = store()
        assertTrue(s.all().isEmpty())
    }

    @Test
    fun `upsert and find by id`() = runTest {
        val s = store()
        s.upsert(sampleTicket(id = 42))
        val found = s.find(42)
        assertNotNull(found)
        assertEquals("Проблема 42", found!!.subject)
    }

    @Test
    fun `find returns null for unknown id`() = runTest {
        val s = store()
        assertNull(s.find(999))
    }

    @Test
    fun `upsert replaces existing ticket`() = runTest {
        val s = store()
        s.upsert(sampleTicket(id = 1, email = "a@x.com").copy(status = "open"))
        s.upsert(sampleTicket(id = 1, email = "a@x.com").copy(status = "resolved"))
        val found = s.find(1)
        assertEquals("resolved", found?.status)
    }

    @Test
    fun `findByEmail matches case-insensitive`() = runTest {
        val s = store()
        s.upsert(sampleTicket(id = 1, email = "Alice@Example.COM"))
        s.upsert(sampleTicket(id = 2, email = "alice@example.com"))
        s.upsert(sampleTicket(id = 3, email = "bob@example.com"))
        val results = s.findByEmail("alice@example.com")
        assertEquals(2, results.size)
    }

    @Test
    fun `addComment appends to history`() = runTest {
        val s = store()
        s.upsert(sampleTicket(id = 1))
        val updated = s.addComment(1, author = "support", text = "Проверяем проблему")
        assertNotNull(updated)
        assertEquals(1, updated!!.history.size)
        assertEquals("support", updated.history[0].author)
        assertEquals("Проверяем проблему", updated.history[0].text)
    }

    @Test
    fun `addComment returns null for unknown ticket`() = runTest {
        val s = store()
        assertNull(s.addComment(999, "x", "y"))
    }

    @Test
    fun `data persists across store instances`() = runTest {
        val file = tempDir.resolve("tickets.json")
        val s1 = TicketStore(file = file)
        s1.upsert(sampleTicket(id = 7))
        // Новый экземпляр с тем же файлом — данные должны сохраниться (atomic write).
        val s2 = TicketStore(file = file)
        assertNotNull(s2.find(7))
    }

    @Test
    fun `corrupt file returns empty list gracefully`() = runTest {
        val file = tempDir.resolve("tickets.json")
        java.nio.file.Files.writeString(file, "{ broken json", Charsets.UTF_8)
        val s = TicketStore(file = file)
        assertTrue(s.all().isEmpty())
    }
}
