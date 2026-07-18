package com.cliagent.llm.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("top_p")
    val topP: Double? = null,
    /**
     * Кросс-провайдерный top-k сэмплинг (ограничивает выбор следующего токена K наиболее вероятными).
     * Часть wire-формата OpenAI-compatible (`top_k`) и Ollama native (`options.top_k`).
     *
     * Default `null` → не пишется в wire (`explicitNulls=false` в клиентских Json), backward-compat с
     * запросами дней 1–29 (только temperature/topP/maxTokens). Провайдеры без top_k-поддержки
     * игнорируют поле (`ignoreUnknownKeys=true` на их стороне, либо просто не применяют).
     */
    @SerialName("top_k")
    val topK: Int? = null,
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
    /**
     * Ollama escape-hatch: произвольный JSON-блок `options` native API Ollama (`/api/chat`). Когда
     * [com.cliagent.llm.OllamaNativeClient] видит non-null [options], он прокидывает их as-is в wire
     * `options` (поверх temperature/top_p/…), позволяя задать нативные параметры (`num_ctx`, `repeat_
     * penalty`, `mirostat`, …), для которых нет плоского поля в [ChatRequest].
     *
     * Default `null` → не пишется в wire (`explicitNulls=false`). Для OpenAI-compatible провайдеров
     * поле игнорируется на уровне клиента (только OllamaNativeClient его читает).
     */
    val options: JsonElement? = null,
    /**
     * Ollama: сколько держать модель загруженной в VRAM после ответа (`keep_alive` native wire-field).
     * Строка-длительность Ollama (`"5m"`, `"10m"`, `"0"` для немедленной выгрузки). Default `null` →
     * не пишется → Ollama использует свой default (5m). Читается только [com.cliagent.llm.OllamaNativeClient].
     */
    @SerialName("keep_alive") val keepAlive: String? = null,
    /**
     * Ollama thinking-модели (qwen3, deepseek-r1): включить reasoning-режим (`think: true` native
     * wire-field). Response содержит отдельное поле `thinking` (internal reasoning) помимо `content`.
     * Default `null` → не пишется → поведение модели по умолчанию. Читается только
     * [com.cliagent.llm.OllamaNativeClient] (OpenAI-compat path не использует).
     */
    val think: Boolean? = null,
)
