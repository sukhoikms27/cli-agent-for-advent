package com.cliagent.llm.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val parentId: String? = null  // для BranchingStrategy (день 10)
)
