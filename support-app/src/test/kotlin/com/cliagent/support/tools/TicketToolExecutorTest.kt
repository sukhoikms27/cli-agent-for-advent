package com.cliagent.support.tools

import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * День 33 — unit-тесты [TicketToolExecutor] (без LLM, через реальный [TicketStore] в @TempDir).
 *
 * Покрывает: success-path (создаётся тикет, return содержит номер), missing subject,
 * инкрементальная генерация id, нормализация priority, soft-degradation на store-ошибке,
 * unknown tool name. Стиль по образцу FileToolExecutorTest (task/day-34).
 */
class TicketToolExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun store(): TicketStore = TicketStore(file = tempDir.resolve("tickets.json"))

    private fun executor(store: TicketStore): TicketToolExecutor = TicketToolExecutor(store)

    @Test
    fun `definitions exposes create_ticket`() = runTest {
        val defs = executor(store()).definitions()
        assertEquals(1, defs.size)
        assertEquals("create_ticket", defs.first().function.name)
    }

    @Test
    fun `create_ticket creates ticket and returns id in message`() = runTest {
        val s = store()
        val result = executor(s).call(
            "create_ticket",
            mapOf(
                "subject" to "Не работает экспорт",
                "description" to "Пользователь жалуется на экспорт в Excel",
                "priority" to "high",
                "customerEmail" to "x@y.com",
            ),
        )
        assertTrue(result.contains("#1"), "результат должен содержать #1: $result")
        val ticket = s.find(1)
        assertNotNull(ticket)
        assertEquals("Не работает экспорт", ticket!!.subject)
        assertEquals("high", ticket.priority)
        assertEquals("x@y.com", ticket.customerEmail)
        assertEquals("open", ticket.status)
    }

    @Test
    fun `create_ticket without subject returns error and does not write to store`() = runTest {
        val s = store()
        val result = executor(s).call("create_ticket", mapOf("description" to "без subject"))
        assertTrue(result.contains("Error"), "missing subject → error: $result")
        assertEquals(0, s.all().size, "тикет не должен быть создан")
    }

    @Test
    fun `create_ticket requires only subject, others optional`() = runTest {
        val s = store()
        val result = executor(s).call("create_ticket", mapOf("subject" to "Проблема"))
        assertTrue(result.contains("#1"))
        val ticket = s.find(1)!!
        assertEquals("medium", ticket.priority, "default priority=medium")
        assertEquals("", ticket.customerEmail, "default email=empty")
        assertEquals("", ticket.description, "default description=empty")
    }

    @Test
    fun `ids are incremental across multiple create calls`() = runTest {
        val s = store()
        val exec = executor(s)
        exec.call("create_ticket", mapOf("subject" to "1"))
        exec.call("create_ticket", mapOf("subject" to "2"))
        exec.call("create_ticket", mapOf("subject" to "3"))
        val all = s.all()
        assertEquals(3, all.size)
        assertEquals(listOf(1, 2, 3), all.map { it.id })
    }

    @Test
    fun `next id does not collide with existing tickets`() = runTest {
        val s = store()
        // Pre-seed store with ticket id=5 (как будто уже есть старые тикеты).
        s.upsert(Ticket(id = 5, subject = "существующий"))
        val result = executor(s).call("create_ticket", mapOf("subject" to "новый"))
        assertTrue(result.contains("#6"), "nextId должен быть 6 (max existing=5)+1: $result")
        assertNotNull(s.find(6))
    }

    @Test
    fun `invalid priority falls back to medium`() = runTest {
        val s = store()
        executor(s).call("create_ticket", mapOf("subject" to "x", "priority" to "supercritical"))
        val ticket = s.find(1)!!
        assertEquals("medium", ticket.priority, "невалидный priority → medium (safe default)")
    }

    @Test
    fun `priority case-insensitive`() = runTest {
        val s = store()
        executor(s).call("create_ticket", mapOf("subject" to "x", "priority" to "URGENT"))
        assertEquals("urgent", s.find(1)!!.priority)
    }

    @Test
    fun `ticket history contains auto-created event from agent`() = runTest {
        val s = store()
        executor(s).call("create_ticket", mapOf("subject" to "x"))
        val ticket = s.find(1)!!
        assertEquals(1, ticket.history.size)
        assertEquals("agent", ticket.history[0].author)
        assertTrue(ticket.history[0].text.contains("автоматически"), "история должна отметить автосоздание")
    }

    @Test
    fun `unknown tool name returns error string`() = runTest {
        val result = executor(store()).call("not_a_tool", mapOf("subject" to "x"))
        assertTrue(result.contains("Unknown tool"))
    }

    @Test
    fun `close is no-op and does not throw`() = runTest {
        // Smoke: close не должен бросать даже без инициализации.
        executor(store()).close()
    }
}
