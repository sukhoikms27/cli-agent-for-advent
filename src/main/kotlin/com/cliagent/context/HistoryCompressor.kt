package com.cliagent.context

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest

data class CompressionResult(
    val summary: String?,
    val recentMessages: List<ChatMessage>,
    val wasCompressed: Boolean,
    val summarizedCount: Int = 0,
    val tokenEstimate: Int = 0
)

class HistoryCompressor(
    private val llmClient: LlmClient,
    private val model: String,
    private val keepRecentCount: Int = 10,
    val compressThreshold: Int = 15
) {
    private val SUMMARIZATION_SYSTEM_PROMPT = """
        Ты — ассистент по сжатию контекста диалога.
        Извлеки ключевую информацию из диалога, структурируя по разделам:

        ## Цели и задачи
        - Какие цели ставил пользователь

        ## Решения и соглашения
        - Какие решения были приняты

        ## Факты и ограничения
        - Важные числа, имена, даты
        - Технические ограничения

        ## Предпочтения
        - Стиль общения, формат ответов

        Не добавляй новую информацию. Будь кратким, но точным.
    """.trimIndent()

    suspend fun compress(
        history: List<ChatMessage>,
        existingSummary: String? = null
    ): CompressionResult {
        if (history.size <= keepRecentCount) {
            return CompressionResult(
                summary = existingSummary,
                recentMessages = history,
                wasCompressed = false,
                summarizedCount = 0,
                tokenEstimate = 0
            )
        }

        val oldMessages = history.dropLast(keepRecentCount)
        val recentMessages = history.takeLast(keepRecentCount)

        val summary = if (existingSummary != null) {
            generateIncrementalSummary(existingSummary, oldMessages)
        } else {
            generateSummary(oldMessages)
        }

        return CompressionResult(
            summary = summary,
            recentMessages = recentMessages,
            wasCompressed = true,
            summarizedCount = oldMessages.size,
            tokenEstimate = estimateTokens(summary)
        )
    }

    private suspend fun generateSummary(messages: List<ChatMessage>): String {
        val prompt = messages.joinToString("\n") { "[${it.role}]: ${it.content}" }

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SUMMARIZATION_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.0
        )

        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> result.data.choices.first().message.content
            is LlmResult.Error -> "[Summary generation failed: ${result.message}]"
        }
    }

    private suspend fun generateIncrementalSummary(
        existingSummary: String,
        newMessages: List<ChatMessage>
    ): String {
        val prompt = """
            Обнови существующее резюме диалога, добавив новую информацию.
            Сохрани все ключевые факты, решения и контекст.

            Существующее резюме:
            $existingSummary

            Новые сообщения:
            ${newMessages.joinToString("\n") { "[${it.role}]: ${it.content}" }}
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = SUMMARIZATION_SYSTEM_PROMPT),
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.0
        )

        return when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> result.data.choices.first().message.content
            is LlmResult.Error -> existingSummary
        }
    }

    private fun estimateTokens(text: String): Int = (text.length / 4) + 4
}
