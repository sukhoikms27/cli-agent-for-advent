package com.cliagent.llm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val stop: List<String>? = null,
    @SerialName("frequency_penalty")
    val frequencyPenalty: Double? = null,
    @SerialName("presence_penalty")
    val presencePenalty: Double? = null,
    val seed: Long? = null,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null
)
