package com.cliagent.web

import kotlinx.serialization.Serializable

/**
 * День 30: DTO для api-эндпоинтов веб-агента «Мотиватор».
 *
 * Вынесены в отдельный файл, чтобы AgentFactory и motivatorModule могли ссылаться на
 * HistoryResponse и HistoryMessage без циклической зависимости от точки входа (main).
 */
@Serializable
data class ChatRequestBody(
    val message: String,
    val temperature: Double = 0.7,
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
