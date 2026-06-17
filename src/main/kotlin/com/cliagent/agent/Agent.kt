package com.cliagent.agent

import com.cliagent.llm.model.ChatMessage

interface Agent {
    suspend fun chat(userMessage: String): String
    suspend fun getHistory(): List<ChatMessage>
    suspend fun reset()
}
