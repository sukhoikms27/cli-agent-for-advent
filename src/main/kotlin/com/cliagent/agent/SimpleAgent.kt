package com.cliagent.agent

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.PromptTemplates
import com.cliagent.llm.model.ReasoningStrategy
import com.cliagent.llm.model.SystemPrompts

class SimpleAgent(
    private val llmClient: LlmClient,
    private val model: String,
    private val systemPrompt: ChatMessage = SystemPrompts.default,
    private val reasoningStrategy: ReasoningStrategy? = null
) : Agent {

    private val history = mutableListOf<ChatMessage>()

    override suspend fun chat(userMessage: String): String {
        // 1. Добавить сообщение пользователя в историю
        val userMsg = ChatMessage(role = "user", content = userMessage)
        history.add(userMsg)

        // 2. Собрать сообщения для запроса
        val messages = buildMessages()

        // 3. Отправить запрос
        val request = ChatRequest(model = model, messages = messages)
        val result = llmClient.chat(request)

        // 4. Обработать ответ
        return when (result) {
            is LlmResult.Success -> {
                val assistantMsg = result.data.choices.first().message
                history.add(assistantMsg)
                assistantMsg.content
            }
            is LlmResult.Error -> "Error: ${result.code} — ${result.message}"
        }
    }

    private fun buildMessages(): List<ChatMessage> {
        val system = if (reasoningStrategy != null) {
            PromptTemplates.buildSystemMessage(reasoningStrategy)
        } else {
            systemPrompt
        }
        return listOf(system) + history.toList()
    }

    override suspend fun getHistory(): List<ChatMessage> = history.toList()

    override suspend fun reset() {
        history.clear()
    }
}
