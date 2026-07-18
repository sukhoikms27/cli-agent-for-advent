package com.cliagent.llm

import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.StreamChunk
import com.cliagent.llm.model.Usage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.math.min

/**
 * День 31: native Ollama client (`/api/chat`). Говорит на native wire-формате Ollama, а не на
 * OpenAI-compat (`/v1/chat/completions`). Native endpoint даёт:
 *  - `options` escape-hatch (num_ctx, repeat_penalty, mirostat, … — параметры без OpenAI-аналога);
 *  - `keep_alive` (сколько держать модель в VRAM после ответа);
 *  - `think` (reasoning-режим qwen3/deepseek-r1, отдельное поле `thinking` в response);
 *  - корректные usage-метрики (`prompt_eval_count`/`eval_count`) даже без `stream_options`.
 *
 * Паттерн HTTP симметричен [OllamaHealthChecker]: injectable [HttpClient] (DI-шов для MockEngine-тестов),
 * собственный [json] с теми же настройками (`ignoreUnknownKeys`/`explicitNulls`/`coerceInputValues`),
 * `withContext(Dispatchers.IO)`, `CancellationException` re-throw (AGENTS.md).
 *
 * Retry-логика дублирована из [OpenAiCompatibleClient] (MAX_ATTEMPTS, isRetryable, экспоненциальный
 * backoff) — сознательная дупликация: вынесение в общий RetryPolicy — TODO будущего рефакторинга
 * (потребует абстракции над «один HTTP-вызов» для обоих клиентов).
 *
 * @param baseUrl native Ollama base (БЕЗ `/v1` суффикса), т.е. `http://localhost:11434`. Конвертация
 *   из OpenAI-compat base (`http://localhost:11434/v1`) — через [nativeBaseFrom].
 * @param http injectable HttpClient (default CIO). Закрывается в [close] только если [ownsClient].
 * @param ownsClient закрывать ли [http] в [close] (true для default-клиента, false для injected).
 */
class OllamaNativeClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultClient(),
    private val ownsClient: Boolean = true,
) : LlmClient, AutoCloseable {

    private val json = Json {
        // AGENTS.md «единый Json»: те же настройки, что у OpenAiCompatibleClient / ConfigRepository.
        // encodeDefaults=true критичен: stream=false (default в OllamaChatRequest) ДОЛЖЕН попасть в wire
        // — иначе Ollama default'ит в stream=true и шлёт NDJSON вместо single-JSON ответа (chat() упадёт).
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Non-streaming chat: `POST {baseUrl}/api/chat` с `stream:false`. Маппит [ChatRequest] → native
     * [OllamaChatRequest] (temperature/top_p/top_k/seed/num_predict/repeat_penalty → `options`;
     * keep_alive; think), парсит [OllamaChatResponse] → [ChatResponse] (id=model+timestamp, choices
     * с message.content, usage из prompt_eval_count/eval_count). Поле `error` → [LlmResult.Error].
     *
     * Retry: до [MAX_ATTEMPTS] с экспоненциальным backoff (как [OpenAiCompatibleClient.chat]).
     * Ретраим только транзиентные (429/5xx/0); 4xx — сразу. `CancellationException` re-throw.
     */
    override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
        var lastError: LlmResult.Error? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val result = executeChatOnce(request)
                if (result is LlmResult.Success) return result
                lastError = result as LlmResult.Error
                // Non-retryable (4xx кроме 429) — отдаём сразу, без задержки.
                if (!isRetryable(result.code)) return result
            } catch (e: CancellationException) {
                throw e   // AGENTS.md: не глотать
            } catch (e: Throwable) {
                // Сетевой сбой (таймаут, обрыв, DNS) — транзиентный, ретраим.
                lastError = LlmResult.Error(0, "Ollama request failed: ${e.message}")
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                val backoff = backoffDelay(attempt)
                delay(backoff)
            }
        }
        return lastError ?: LlmResult.Error(0, "Retry exhausted with no error captured")
    }

    /** Один HTTP-вызов к Ollama без retry. */
    private suspend fun executeChatOnce(request: ChatRequest): LlmResult<ChatResponse> = try {
        val response: HttpResponse = http.post("$baseUrl/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(OllamaChatRequest.serializer(), request.toWire(stream = false)))
        }
        val bodyText = response.bodyAsText()
        val parsed = json.decodeFromString(OllamaChatResponse.serializer(), bodyText)
        // Ollama при ошибке (model not found, crash) может вернуть HTTP 200 с {"error":"..."}.
        if (!parsed.error.isNullOrBlank()) {
            // 500-подобный код — retryable (модель может ещё грузиться); используем 0 для network-ish.
            val code = if (response.status.value >= 500) response.status.value else 0
            LlmResult.Error(code, parsed.error)
        } else {
            LlmResult.Success(parsed.toChatResponse())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        LlmResult.Error(0, "Ollama request failed: ${e.message}")
    }

    /**
     * Streaming chat: `POST {baseUrl}/api/chat` с `stream:true`. Ollama шлёт **NDJSON** (один JSON-
     * объект на строку, последний с `done:true`), НЕ SSE (`data:`-префиксов нет).
     *
     * Маппинг:
     *  - `message.content` непустой → [StreamChunk.Delta] (incremental content, как OpenAI delta).
     *  - `thinking` непустой → [StreamChunk.Reasoning] (qwen3 reasoning-режим, progressive thinking).
     *  - `done==true` → [StreamChunk.Done] (usage из prompt_eval_count/eval_count, finishReason="stop")
     *    + return (терминальный маркер потока).
     *
     * HTTP non-2xx ДО начала потока → [StreamChunk.Error]. Без retry (как [OpenAiCompatibleClient.chatStream]):
     * retry после первого токена = дубликат prefix в выводе caller'а. `CancellationException` re-throw.
     *
     * Возвращает cold [Flow]; выполнение начинается только при collect, на [Dispatchers.IO].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun chatStream(request: ChatRequest): Flow<StreamChunk> = flow {
        val wireRequest = request.toWire(stream = true)
        // HttpStatement (preparePost + execute) НЕ материализует body — даёт streaming HttpResponse с
        // открытым channel (как OpenAiCompatibleClient.executeStream). per-request timeout=INFINITE —
        // для стриминга thinking-ответов в 90+ секунд (requestTimeout=120s на HttpClient непригоден).
        val finalChunk: StreamChunk? = try {
            http.preparePost("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(OllamaChatRequest.serializer(), wireRequest))
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            }.execute { response: HttpResponse ->
                // HTTP-ошибка до начала потока → Error-chunk.
                if (!response.status.isSuccess()) {
                    val errBody = runCatching { response.bodyAsText() }.getOrDefault("")
                    emit(StreamChunk.Error(response.status.value, errBody))
                    return@execute null   // Done уже не нужен (поток оборван ошибкой)
                }
                val channel: ByteReadChannel = response.body()
                var lastUsage: Usage? = null
                try {
                    while (!channel.isClosedForRead) {
                        val line = channel.readLine() ?: break
                        if (line.isBlank()) continue   // пустые строки между NDJSON-объектами
                        val chunk = try {
                            json.decodeFromString(OllamaStreamChunk.serializer(), line)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            // Мусорная/partial строка — пропускаем, не роняем поток (resilience).
                            continue
                        }
                        if (!chunk.error.isNullOrBlank()) {
                            emit(StreamChunk.Error(0, chunk.error))
                            return@execute null
                        }
                        chunk.message?.content?.takeIf { it.isNotEmpty() }?.let { emit(StreamChunk.Delta(it)) }
                        chunk.thinking?.takeIf { it.isNotEmpty() }?.let { emit(StreamChunk.Reasoning(it)) }
                        if (chunk.done) {
                            lastUsage = chunk.toUsage()
                            emit(StreamChunk.Done(lastUsage, finishReason = "stop"))
                            return@execute null
                        }
                    }
                    // Graceful fallback: поток закончился без done=true. Эмитим Done — терминальный
                    // маркер гарантирован (симметрично OpenAiCompatibleClient graceful-fallback).
                    StreamChunk.Done(lastUsage, finishReason = "stop")
                } catch (e: CancellationException) {
                    throw e
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(StreamChunk.Error(0, "Ollama stream request failed: ${e.message}"))
            null
        }
        // finalChunk != null только при graceful-fallback; в остальных путях Done/Error эмитнуты внутри.
        if (finalChunk is StreamChunk.Done) emit(finalChunk)
    }.flowOn(Dispatchers.IO)

    override fun close() {
        if (ownsClient) runCatching { http.close() }
    }

    // ── retry helpers (дублированы из OpenAiCompatibleClient — TODO общий RetryPolicy) ──

    private fun isRetryable(code: Int): Boolean = code == 429 || code >= 500 || code == 0

    private fun backoffDelay(attempt: Int): Long {
        val raw = (1L shl attempt) * BASE_DELAY_MS
        return min(raw, BACKOFF_CAP_MS)
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
        const val BASE_DELAY_MS = 1_000L
        const val BACKOFF_CAP_MS = 30_000L
    }

    // ── ChatRequest → OllamaChatRequest mapping ─────────────────────────────────

    /**
     * Маппит кросс-провайдерный [ChatRequest] в native Ollama wire-формат. Sampling-поля (temperature/
     * top_p/top_k/seed/num_predict/repeat_penalty) собираются в `options`; Ollama-специфичные
     * (keep_alive, think) идут top-level. tools/tool_calls маппятся минимально (Ollama native format).
     *
     * **День 29: ModelDefaults fallback-слой.** Когда поле [ChatRequest] == null (не задано CLI-флагом
     * или config sampling), подставляется [ModelDefaultsRegistry.forModel] (per-model tuned defaults).
     * Приоритет: CLI flag > config sampling > ModelDefaults > null (server-default Ollama). Это держит
     * provider-агностику агента ([com.cliagent.agent.ContextAwareAgent] не знает про defaults — они
     * резолвятся в wire-слое). Критично: [ModelDefaults.numCtx] задаётся ВСЕГДА из defaults (нет плоского
     * поля в ChatRequest) — иначе Ollama server-default ~4096 молчаливо режет RAG-context.
     *
     * **repeat_penalty fix (день 29):** ранее маппился `frequencyPenalty` (OpenAI `frequency_penalty`,
     * диапазон -2..2, штраф за frequency) → это semantic mismatch с llama.cpp `repeat_penalty` (диапазон
     * ~0.5..2.0, штраф за повтор любого токена). Теперь repeat_penalty берётся из [ModelDefaults], а
     * frequencyPenalty НЕ маппится вовсе (Ollama не имеет прямого аналога).
     */
    private fun ChatRequest.toWire(stream: Boolean): OllamaChatRequest {
        val defaults = ModelDefaultsRegistry.forModel(model)
        val options = OllamaOptions(
            temperature = temperature ?: defaults.temperature,
            topP = topP ?: defaults.topP,
            topK = topK ?: defaults.topK,
            seed = seed,
            numPredict = maxTokens ?: defaults.numPredict,
            numCtx = defaults.numCtx,
            repeatPenalty = defaults.repeatPenalty,   // НЕ frequencyPenalty — разные параметры (см. KDoc)
            stop = stop,
        )
        return OllamaChatRequest(
            model = model,
            messages = messages.map { it.toWire() },
            stream = stream,
            options = if (options.hasAny()) options else null,
            keepAlive = keepAlive ?: defaults.keepAlive,
            think = think,
            tools = tools?.map { it.toWire() },
        )
    }

    private fun ChatMessage.toWire(): OllamaMessage {
        val content = content
        // tool_calls маппятся минимально: Ollama native format для function-calling отличается от
        // OpenAI; для MVP прокидываем role+content (основной use-case OllamaNativeClient — RAG/free chat).
        return OllamaMessage(role = role, content = content)
    }

    private fun com.cliagent.llm.model.ToolDefinition.toWire(): OllamaTool =
        OllamaTool(type = "function", function = OllamaFunction(name = function.name, description = function.description))

    // ── OllamaChatResponse → ChatResponse mapping ───────────────────────────────

    private fun OllamaChatResponse.toChatResponse(): ChatResponse {
        // День 31 (регрессия): маппим native tool_calls из Ollama response в [ChatMessage.toolCalls].
        // Раньше тут был toolCalls=null → ContextAwareAgent.runToolLoop никогда не стартовал → MCP-тулы
        // (4 server'а) были недоступны для локальной модели. Ollama native tool_call = {function:{name,
        // arguments}}, БЕЗ id/type (OpenAI требует оба): id генерируем "ollama-<name>-<i>", type
        // дефолтится в ToolCall ("function"). arguments — JSON-объект в Ollama (JsonElement) → конвертим
        // в String (toString() даёт `{"query":"..."}`) — это формат, который ожидает агент (парсит как JSON).
        // Sealed Result (AGENTS.md): пустые tool_calls → null (не throw для flow control).
        val toolCalls = message?.toolCalls?.takeIf { it.isNotEmpty() }?.mapIndexed { i, tc ->
            com.cliagent.llm.model.ToolCall(
                id = "ollama-${tc.function.name}-$i",
                type = "function",
                function = com.cliagent.llm.model.ToolCallFunction(
                    name = tc.function.name,
                    arguments = tc.function.arguments?.toString() ?: "",
                ),
            )
        }
        val assistantMessage = ChatMessage(
            role = "assistant",
            content = message?.content ?: "",
            toolCalls = toolCalls,
        )
        return ChatResponse(
            id = "${model}-${createdAt}",
            choices = listOf(
                Choice(index = 0, message = assistantMessage, finishReason = if (done) "stop" else null)
            ),
            usage = toUsage(),
        )
    }

    private fun OllamaChatResponse.toUsage(): Usage? {
        val prompt = promptEvalCount ?: 0
        val completion = evalCount ?: 0
        // Если ни одной метрики нет — null (как OpenAiCompatibleClient при отсутствии usage).
        return if (promptEvalCount == null && evalCount == null) null
            else Usage(promptTokens = prompt, completionTokens = completion, totalTokens = prompt + completion)
    }

    private fun OllamaStreamChunk.toUsage(): Usage? {
        val prompt = promptEvalCount ?: 0
        val completion = evalCount ?: 0
        return if (promptEvalCount == null && evalCount == null) null
            else Usage(promptTokens = prompt, completionTokens = completion, totalTokens = prompt + completion)
    }

    // ── wire DTOs (private, симметрично PingRequest в OllamaHealthChecker) ──────

    @Serializable
    private data class OllamaChatRequest(
        val model: String,
        val messages: List<OllamaMessage>,
        val stream: Boolean = false,
        val options: OllamaOptions? = null,
        @SerialName("keep_alive") val keepAlive: String? = null,
        val think: Boolean? = null,
        val format: String? = null,
        val tools: List<OllamaTool>? = null,
    )

    @Serializable
    private data class OllamaMessage(
        val role: String,
        val content: String,
        // День 31: native tool_calls в response-сообщении ассистента. Ollama шлёт [{function:{name,arguments}}],
        // БЕЗ id/type (OpenAI требует оба). id генерируется в toChatResponse ("ollama-<name>-<i>"),
        // type дефолтится в ToolCall ("function"). В request-стороне toWire() это поле не пишет
        // (tool_calls от LLM → только в response; assistant echo в history через batch ChatMessage).
        @SerialName("tool_calls") val toolCalls: List<OllamaToolCall>? = null,
    )

    /**
     * День 31: один native tool_call в ответе Ollama. В отличие от OpenAI `tool_calls[].{id,type,function}`,
     * Ollama шлёт только `function` (без id и type). См. [OllamaMessage.toolCalls].
     */
    @Serializable
    private data class OllamaToolCall(
        val function: OllamaToolFunction,
    )

    /**
     * День 31: name+arguments tool_call'а. `arguments` — JsonElement: разные версии Ollama шлют
     * либо JSON-объект (`{"query":"..."}`), либо строку (`"{\"query\":\"...\"}"`). JsonElement гибко
     * покрывает оба; в toChatResponse конвертируется в String через toString() для целевого
     * [com.cliagent.llm.model.ToolCallFunction.arguments] (который — JSON-строка, парсится агентом).
     */
    @Serializable
    private data class OllamaToolFunction(
        val name: String,
        val arguments: JsonElement? = null,
    )

    @Serializable
    private data class OllamaOptions(
        val temperature: Double? = null,
        @SerialName("top_p") val topP: Double? = null,
        @SerialName("top_k") val topK: Int? = null,
        val seed: Long? = null,
        @SerialName("num_predict") val numPredict: Int? = null,
        @SerialName("num_ctx") val numCtx: Int? = null,
        @SerialName("repeat_penalty") val repeatPenalty: Double? = null,
        val stop: List<String>? = null,
    ) {
        /** true если хоть одно поле set — иначе не сериализуем options (compact wire). */
        fun hasAny(): Boolean = temperature != null || topP != null || topK != null ||
            seed != null || numPredict != null || numCtx != null || repeatPenalty != null ||
            !stop.isNullOrEmpty()
    }

    @Serializable
    private data class OllamaTool(
        val type: String = "function",
        val function: OllamaFunction,
    )

    @Serializable
    private data class OllamaFunction(
        val name: String,
        val description: String? = null,
    )

    @Serializable
    private data class OllamaChatResponse(
        val model: String = "",
        @SerialName("created_at") val createdAt: String = "",
        val message: OllamaMessage? = null,
        val thinking: String? = null,
        val done: Boolean = false,
        @SerialName("eval_count") val evalCount: Int? = null,
        @SerialName("eval_duration") val evalDuration: Long? = null,
        @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
        @SerialName("prompt_eval_duration") val promptEvalDuration: Long? = null,
        @SerialName("total_duration") val totalDuration: Long? = null,
        val error: String? = null,
    )

    /** Та же shape, что OllamaChatResponse, но message.content — incremental (per NDJSON-строка). */
    @Serializable
    private data class OllamaStreamChunk(
        val model: String = "",
        val message: OllamaMessage? = null,
        val thinking: String? = null,
        val done: Boolean = false,
        @SerialName("eval_count") val evalCount: Int? = null,
        @SerialName("eval_duration") val evalDuration: Long? = null,
        @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
        val error: String? = null,
    )
}

/** Default HttpClient для OllamaNativeClient — копия паттерна OllamaHealthChecker. */
private fun defaultClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 120_000
    }
}
