package com.cliagent.llm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String = "",
    val parentId: String? = null,  // для BranchingStrategy (день 10)
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,   // день 17: запрос tool'а от ассистента
    @SerialName("tool_call_id")
    val toolCallId: String? = null           // день 17: для role="tool" (ответ на tool_call)
)
