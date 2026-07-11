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
    val toolChoice: String? = null,
    /**
     * День 30 (streaming SSE): включить server-sent events режим (`"stream":true` в wire-формате).
     * Default `null` → НЕ пишется в JSON (`explicitNulls=false` в клиентском Json OpenAiCompatibleClient)
     * → существующие не-streaming [com.cliagent.llm.LlmClient.chat] запросы не меняют wire-формат
     * (backward-compat дней 1–29). [OpenAiCompatibleClient.chatStream] форсирует `true` через copy().
     */
    val stream: Boolean? = null,
    /**
     * День 30: опции стрима (`stream_options`), в основном `include_usage=true` — финальный фрейм
     * с usage для [com.cliagent.llm.token.TokenCounter.recordUsage]. null → не пишется (backward-compat).
     */
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)
