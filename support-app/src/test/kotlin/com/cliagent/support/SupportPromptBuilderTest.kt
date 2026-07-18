package com.cliagent.support

import com.cliagent.llm.model.SystemPrompts
import com.cliagent.support.tickets.Ticket
import com.cliagent.support.tickets.TicketEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 33 — unit-тесты [buildSupportSystemPrompt] (чистая функция сборки system-prompt с контекстом
 * тикета). Без IO, без LLM.
 */
class SupportPromptBuilderTest {

    private val basePrompt = SystemPrompts.supportAgent.content

    @Test
    fun `null ticket returns base supportAgent prompt`() {
        val msg = buildSupportSystemPrompt(null)
        assertEquals(basePrompt, msg.content)
    }

    @Test
    fun `ticket context block is appended when ticket provided`() {
        val ticket = Ticket(
            id = 42,
            subject = "Проблема с входом",
            status = "open",
            priority = "high",
            customerEmail = "x@y.com",
            description = "Не могу войти",
        )
        val msg = buildSupportSystemPrompt(ticket)
        assertTrue(msg.content.startsWith(basePrompt))
        assertTrue(msg.content.contains("[Ticket context]"))
        assertTrue(msg.content.contains("Ticket #42"))
        assertTrue(msg.content.contains("Проблема с входом"))
        assertTrue(msg.content.contains("Status: open"))
        assertTrue(msg.content.contains("Priority: high"))
        assertTrue(msg.content.contains("Не могу войти"))
    }

    @Test
    fun `customer info included when name present`() {
        val ticket = Ticket(
            id = 1, subject = "x", customerEmail = "a@b.com", customerName = "Алиса",
        )
        val msg = buildSupportSystemPrompt(ticket)
        assertTrue(msg.content.contains("Customer: Алиса <a@b.com>"))
    }

    @Test
    fun `customer info omitted when name null`() {
        val ticket = Ticket(
            id = 1, subject = "x", customerEmail = "a@b.com", customerName = null,
        )
        val msg = buildSupportSystemPrompt(ticket)
        assertFalse(msg.content.contains("Customer:"))
    }

    @Test
    fun `ticket history included when non-empty`() {
        val ticket = Ticket(
            id = 1,
            subject = "x",
            history = listOf(
                TicketEvent(author = "customer", text = "Помогите!", at = "2026-07-18T10:00:00Z"),
                TicketEvent(author = "support", text = "Уже разбираемся", at = "2026-07-18T10:05:00Z"),
            ),
        )
        val msg = buildSupportSystemPrompt(ticket)
        assertTrue(msg.content.contains("History:"))
        assertTrue(msg.content.contains("Помогите!"))
        assertTrue(msg.content.contains("Уже разбираемся"))
    }

    @Test
    fun `ticket history section omitted when empty`() {
        val ticket = Ticket(id = 1, subject = "x", history = emptyList())
        val msg = buildSupportSystemPrompt(ticket)
        assertFalse(msg.content.contains("History:"))
    }

    @Test
    fun `supportAgent system prompt exists with required rules`() {
        // Sanity: промпт должен содержать ключевые правила поддержки.
        assertNotNull(SystemPrompts.supportAgent)
        val content = SystemPrompts.supportAgent.content
        assertTrue(content.contains("не знаю", ignoreCase = true) || content.contains("не выдумывай", ignoreCase = true))
        assertTrue(content.contains("Retrieved context"))
        assertTrue(content.contains("Ticket context"))
    }
}
