package com.cliagent.llm

import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse

interface LlmClient {
    suspend fun chat(request: ChatRequest): LlmResult<ChatResponse>

    // Phase 2: streaming placeholder
    // fun chatStream(request: ChatRequest): Flow<StreamChunk>
}
