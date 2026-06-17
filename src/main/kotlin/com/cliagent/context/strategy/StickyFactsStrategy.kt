package com.cliagent.context.strategy

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest

class StickyFactsStrategy(
    private val llmClient: LlmClient,
    private val model: String,
    private val windowSize: Int = 10
) : ContextStrategy {

    private val facts = mutableMapOf<String, String>()

    override fun buildMessages(
        history: List<ChatMessage>,
        newMessage: ChatMessage,
        systemPrompt: ChatMessage
    ): List<ChatMessage> {
        val recentMessages = history.takeLast(windowSize)
        val factsMessage = buildFactsMessage()
        return listOf(systemPrompt, factsMessage) + recentMessages + newMessage
    }

    override fun getName(): String = "facts"

    override fun getDescription(): String =
        "Extracts key facts via LLM + keeps last $windowSize messages"

    override fun needsCompression(): Boolean = facts.size > 50

    override suspend fun onAssistantResponse(assistantMessage: ChatMessage) {
        updateFacts(assistantMessage)
    }

    private suspend fun updateFacts(recentMessage: ChatMessage) {
        val extractionPrompt = """
            Извлеки ключевые факты из диалога.
            Каждый факт — пара ключ:значение.
            Извлекай только НОВЫЕ или ОБНОВЛЁННЫЕ факты.
            Включи: цели, ограничения, предпочтения, решения, важные числа/имена.

            Текущие факты:
            ${facts.entries.joinToString("\n") { "${it.key}: ${it.value}" }}

            Последнее сообщение:
            [${recentMessage.role}]: ${recentMessage.content}

            Выведи только обновлённый список фактов в формате:
            key1: value1
            key2: value2
        """.trimIndent()

        val request = ChatRequest(
            model = model,
            messages = listOf(ChatMessage(role = "user", content = extractionPrompt)),
            temperature = 0.0
        )

        when (val result = llmClient.chat(request)) {
            is LlmResult.Success -> {
                val factsText = result.data.choices.first().message.content
                parseFacts(factsText)
            }
            is LlmResult.Error -> { /* keep facts as-is */ }
        }
    }

    private fun buildFactsMessage(): ChatMessage {
        if (facts.isEmpty()) {
            return ChatMessage(role = "system", content = "[No facts recorded yet]")
        }
        val factsText = facts.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        return ChatMessage(role = "system", content = "[Key facts from conversation]\n$factsText")
    }

    private fun parseFacts(factsText: String) {
        factsText.lineSequence().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().removePrefix("- ")
                val value = parts[1].trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    facts[key] = value
                }
            }
        }
    }

    fun getFacts(): Map<String, String> = facts.toMap()

    fun setFacts(loadedFacts: Map<String, String>) {
        facts.clear()
        facts.putAll(loadedFacts)
    }

    override fun reset() {
        facts.clear()
    }
}
