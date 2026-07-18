package com.cliagent.support

import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.SystemPrompts
import com.cliagent.support.tickets.Ticket

/**
 * День 33 — чистая функция сборки system-prompt support-agent'а. Вынесена из
 * [SupportAgentFactory.buildSupportSystemPrompt] для unit-тестирования (без IO, детерминированная).
 *
 * Базовый [SystemPrompts.supportAgent] + опциональный блок [Ticket context] с данными тикета.
 */
internal fun buildSupportSystemPrompt(ticket: Ticket?): ChatMessage {
    if (ticket == null) return SystemPrompts.supportAgent
    val ticketBlock = buildString {
        appendLine()
        appendLine("[Ticket context]")
        appendLine("Ticket #${ticket.id}: ${ticket.subject}")
        appendLine("Status: ${ticket.status}")
        appendLine("Priority: ${ticket.priority}")
        if (ticket.customerName != null) appendLine("Customer: ${ticket.customerName} <${ticket.customerEmail}>")
        appendLine("Description: ${ticket.description}")
        if (ticket.history.isNotEmpty()) {
            appendLine("History:")
            ticket.history.forEach { e -> appendLine("  - [${e.at}] ${e.author}: ${e.text}") }
        }
    }
    return SystemPrompts.supportAgent.copy(content = SystemPrompts.supportAgent.content + ticketBlock)
}
