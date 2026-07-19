package com.cliagent.support

import kotlinx.serialization.Serializable

/**
 * День 33 — DTO для api-эндпоинтов support-app.
 *
 * Зеркалирует структуру web-app/Dto.kt, плюс поля для контекста тикета (ticketId) и флаг эскалации.
 */
@Serializable
data class SupportRequest(
    val message: String,
    val ticketId: Int? = null,
    val customerEmail: String? = null,
)

@Serializable
data class DoneEvent(val full: String)

@Serializable
data class SessionResponse(val sessionId: String)

@Serializable
data class ApiError(val error: String)

@Serializable
data class HistoryMessage(
    val role: String,
    val content: String,
)

@Serializable
data class HistoryResponse(
    val messages: List<HistoryMessage>,
)

@Serializable
data class TokenChunk(val token: String)

@Serializable
data class TicketSummary(
    val id: Int,
    val subject: String,
    val status: String,
    val priority: String,
)
