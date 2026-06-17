package com.cliagent.memory

import com.cliagent.llm.model.ChatMessage
import kotlinx.serialization.Serializable

@Serializable
data class ChatData(
    val id: String,
    val title: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val summary: String? = null,
    val facts: Map<String, String> = emptyMap(),
    val branches: List<BranchData> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class BranchData(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val leafMessageId: String? = null,
    val fromIndex: Int,
    val createdAt: String
)
