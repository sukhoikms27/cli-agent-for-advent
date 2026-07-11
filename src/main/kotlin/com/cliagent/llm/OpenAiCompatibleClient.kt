package com.cliagent.llm

import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.StreamChatChunk
import com.cliagent.llm.model.StreamChunk
import com.cliagent.llm.model.StreamOptions
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlin.math.min

class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    /**
     * День 30 (streaming SSE): injectable HttpClient для тестов (MockEngine). Default — текущий
     * конфиг CIO + ContentNegotiation/json + HttpTimeout (идентичный бывшему приватному полю,
     * 0 регрессий дней 1–29). Существующий вызов [LlmClientFactory.create]
     * (`OpenAiCompatibleClient(baseUrl, apiKey)`) продолжает работать без изменений.
     *
     * DI-шов симметричен [com.cliagent.rag.embedding.OllamaEmbeddingClient.http]: тесты SSE-парсера
     * подставляют `HttpClient(MockEngine { respond(sseBody) })` без реальной сети/Ollama.
     */
    private val httpClient: HttpClient = defaultHttpClient(),
) : LlmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        // День 17: при tool_calls ответ LLM может содержать "content": null. content — non-null
        // (String = "") → coerceInputValues превращает null в default ("") вместо ошибки парсинга.
        coerceInputValues = true
    }

    /**
     * День 19 (progressive retry): единый retry-слой на границе [LlmClient]. Покрывает **все** вызовы
     * — чат, stage-флоу, рой, классификаторы, инварианты — без дублирования логики в каждом слое.
     *
     * До [MAX_ATTEMPTS] попыток с экспоненциальным backoff ([backoffDelay]). Ретраим только
     * **транзиентные** ошибки ([isRetryable]): 429 (rate limit), 5xx (server), таймаут/обрыв сети.
     * Не ретраим 4xx (кроме 429) — это конфиг/запрос, повтор не поможет (401 bad key, 400 bad request).
     *
     * `CancellationException` **никогда не глотаем** (конвенция проекта, AGENTS.md): re-throw до retry.
     */
    override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
        var lastError: LlmResult.Error? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val result = executeOnce(request)
                if (result is LlmResult.Success) return result
                lastError = result as LlmResult.Error
                // Non-retryable → отдаём сразу, без задержки (4xx не поправится повтором).
                if (!isRetryable(result.code)) return result
            } catch (e: CancellationException) {
                throw e   // корутин-отмена — не retry, не глотать (AGENTS.md)
            } catch (e: Throwable) {
                // Сетевой сбой (таймаут, обрыв соединения, DNS) — транзиентный, ретраим.
                lastError = LlmResult.Error(0, "Request failed: ${e.message}")
            }
            // Последняя попытка — задержку не делаем, сразу вернём lastError ниже.
            if (attempt < MAX_ATTEMPTS - 1) {
                val backoff = backoffDelay(attempt)
                System.err.println("[retry] LLM call failed (attempt ${attempt + 1}/$MAX_ATTEMPTS, " +
                    "code=${lastError.code}); retrying in ${backoff}ms…")
                delay(backoff)
            }
        }
        return lastError ?: LlmResult.Error(0, "Retry exhausted with no error captured")
    }

    /**
     * День 30 (streaming SSE): серверный стриминг ответа. Решает проблему thinking-моделей
     * (qwen3:14b: 40-90с «пустоты» до первого токена) — токены идут по мере генерации через SSE
     * `data:`-фреймы, пользователь видит контент сразу. SocketTimeout измеряет время МЕЖДУ байтами →
     * пока токены идут, он не срабатывает (главная польза streaming для долгих thinking-ответов).
     *
     * **Без retry** (в отличие от [chat]): retry после первого токена = дубликат prefix в выводе
     * caller'а (progressive render уже напечатал часть). Сетевые ошибки ДО первого токена идут как
     * [StreamChunk.Error] — caller решает реакцию (как с [LlmResult.Error] в не-streaming пути).
     *
     * Форсирует `stream=true` + `stream_options.include_usage=true` через [ChatRequest.copy] —
     * caller не обязан помнить про эти флаги. Возвращает cold [Flow]; выполнение (и сетевой запрос)
     * начинается только при collect, на [Dispatchers.IO] (`flowOn`).
     *
     * Гарантии потока:
     *  - [StreamChunk.Done] эмиссия ровно одна — при `data: [DONE]` ИЛИ graceful fallback (поток
     *    закончился без явного DONE — некоторые провайдеры игнорируют спецификацию OpenAI-SSE).
     *  - [kotlinx.coroutines.CancellationException] НЕ глотается (AGENTS.md) — пробрасывается.
     *  - HTTP 4xx/5xx до начала потока → [StreamChunk.Error] с кодом и телом ответа.
     */
    override fun chatStream(request: ChatRequest): Flow<StreamChunk> =
        executeStream(
            request.copy(
                stream = true,
                streamOptions = StreamOptions(includeUsage = true),
            )
        )

    /**
     * Низкоуровневый SSE-парсинг: HttpStatement.execute + ByteReadChannel loop. Вынесено из
     * [chatStream] для тестируемости (DI HttpClient с MockEngine) и未来их провайдеров (Anthropic/
     * Gemini имеют другой wire-формат, но тот же стриминг-контракт [StreamChunk]).
     *
     * Per-request `timeout { requestTimeoutMillis = 0 }` — 0 = infinite для стриминга (документировано
     * Ktor HttpTimeout): клиентский requestTimeout=120s непригоден для thinking-ответа в 90+ секунд.
     * connectTimeout/socketTimeout наследуются из HttpClient (10s connect / 120s между байтами).
     *
     * SSE-формат (OpenAI-compatible, Ollama /v1/chat/completions с stream:true):
     * ```
     * data: {"id":"...","choices":[{"index":0,"delta":{"content":"Hi"}}]}
     * <blank line>
     * : keep-alive comment (опционально, Ollama шлёт для удержания соединения)
     * data: [DONE]
     * ```
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun executeStream(request: ChatRequest): Flow<StreamChunk> = flow {
        // HttpStatement НЕ материализует body — даёт streaming HttpResponse с открытым channel.
        // execute { response -> } — suspending lambda; emit() внутри работает (flow builder suspend).
        val finalChunk: StreamChunk? = httpClient.preparePost("$baseUrl/chat/completions") {
            if (apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(request)
            // INFINITE_TIMEOUT_MS = бесконечный request timeout для стриминга (90+ секунд thinking).
            // В Ktor 3.4.3 `0` невалиден (HttpTimeout требует >0; INFINITE_TIMEOUT_MS=Long.MAX_VALUE).
            // connectTimeout/socketTimeout наследуются из HttpClient (10s connect / 120s между байтами).
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            // HTTP-ошибка до начала потока → Error-chunk (caller решает реакцию, как LlmResult.Error).
            if (!response.status.isSuccess()) {
                val errBody = runCatching { response.bodyAsText() }.getOrDefault("")
                emit(StreamChunk.Error(response.status.value, errBody))
                return@execute null   // сигнал: Done уже не нужен (поток оборван ошибкой)
            }

            val channel: ByteReadChannel = response.body()
            var lastUsage: com.cliagent.llm.model.Usage? = null
            var lastFinishReason: String? = null
            var sawDone = false

            while (!channel.isClosedForRead) {
                val line = channel.readLine() ?: break
                when {
                    line.isEmpty() -> continue                       // разделитель фреймов SSE
                    line.startsWith(":") -> continue                  // keep-alive comment
                    !line.startsWith("data:") -> continue             // неизвестный event-тип
                    else -> {
                        val payload = line.removePrefix("data:").trim()
                        when {
                            payload == "[DONE]" -> {
                                emit(StreamChunk.Done(lastUsage, lastFinishReason))
                                sawDone = true
                                return@execute null   // Done уже эмитнут; finalChunk=null → без дубликата
                            }
                            payload.isEmpty() -> continue             // пустой data: — пропускаем
                            else -> {
                                try {
                                    val chunk = json.decodeFromString<StreamChatChunk>(payload)
                                    val choice = chunk.choices.firstOrNull()
                                    // Delta-эмиссия только при непустом content (role-фрейм первого
                                    // токена без content пропускается, как и финальный finish_reason-фрейм).
                                    val content = choice?.delta?.content
                                    if (!content.isNullOrEmpty()) {
                                        emit(StreamChunk.Delta(content))
                                    }
                                    // Reasoning-эмиссия: qwen3 thinking-модели отдают внутренний reasoning
                                    // отдельно (delta.reasoning) — пользователь видит progressive «размышления»
                                    // ДО финального content. Без этого стриминг thinking-модели = пустота.
                                    val reasoning = choice?.delta?.reasoning
                                    if (!reasoning.isNullOrEmpty()) {
                                        emit(StreamChunk.Reasoning(reasoning))
                                    }
                                    choice?.finishReason?.let { lastFinishReason = it }
                                    chunk.usage?.let { lastUsage = it }
                                } catch (e: CancellationException) {
                                    throw e   // AGENTS.md: никогда не глотать
                                } catch (e: Throwable) {
                                    // Мусорный/неполный фрейм — пропускаем, не роняем поток (resilience).
                                    // SSE-стрим может содержать partial-чанки при обрыве сети; один
                                    // битый фрейм не должен валить весь ответ.
                                }
                            }
                        }
                    }
                }
            }
            // Graceful: поток закончился без явного `data: [DONE]` (провайдер не следует спецификации
            // OpenAI-SSE строго, или соединение закрыто сервером после последнего токена). Эмитим Done
            // с накопленными usage/finishReason — caller получает терминальный маркер в любом случае.
            if (!sawDone) StreamChunk.Done(lastUsage, lastFinishReason) else null
        }

        // finalChunk != null только при graceful-fallback (поток без [DONE]). В остальных путях
        // Done/Error уже эмитнуты внутри execute lambda; null = ничего не делаем.
        if (finalChunk is StreamChunk.Done) {
            emit(finalChunk)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Один HTTP-вызов к LLM без retry. Возвращает [LlmResult]: Success с распарсенным ответом или
     * Error с кодом/сообщением (HTTP 4xx/5xx, таймаут, мусорный ответ).
     */
    private suspend fun executeOnce(request: ChatRequest): LlmResult<ChatResponse> = try {
        val response: HttpResponse = httpClient.post("$baseUrl/chat/completions") {
            // День 25: auth только при наличии apiKey. Локальная Ollama работает без auth — пустой
            // `Bearer ` header может ломать некоторых провайдеров, поэтому не шлём его вовсе.
            if (apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val bodyText = response.bodyAsText()
        try {
            LlmResult.Success(json.decodeFromString<ChatResponse>(bodyText))
        } catch (e: Exception) {
            LlmResult.Error(0, "Failed to parse response: ${e.message}")
        }
    } catch (e: ClientRequestException) {
        val statusCode = e.response.status.value
        val errorBody = runCatching { e.response.bodyAsText() }.getOrDefault("")
        val message = when (statusCode) {
            401 -> if (apiKey.isBlank()) {
                // День 25: локальный провайдер (Ollama) обычно без auth. 401 здесь — нетрадиционен.
                "Server returned 401 Unauthorized. For local Ollama, ensure no reverse-proxy auth is in front; " +
                    "for cloud providers, set CLI_AGENT_API_KEY."
            } else {
                "Invalid API key. Check CLI_AGENT_API_KEY environment variable."
            }
            429 -> "Rate limit exceeded. Try again later."
            else -> "Client error: $errorBody"
        }
        LlmResult.Error(statusCode, message)
    } catch (e: ServerResponseException) {
        LlmResult.Error(e.response.status.value, "Server error: ${e.response.bodyAsText()}")
    } catch (e: Exception) {
        // Сетевой сбой (таймаут, обрыв, DNS) — внешний retry-цикл решит, ретраить ли.
        LlmResult.Error(0, "Request failed: ${e.message}")
    }

    /**
     * Транзиентная ли ошибка (повтор может помочь): 429 (rate limit), 5xx (server fault),
     * таймаут/обрыв сети (code=0). 4xx (кроме 429) — не ретраим: конфиг/запрос не поправится повтором.
     *
     * `internal` — покрывается unit-тестом (без HTTP-мока) на чистой классификации кодов.
     */
    internal fun isRetryable(code: Int): Boolean =
        code == 429 || code >= 500 || code == 0

    /**
     * Экспоненциальный backoff: `2^attempt × BASE`, ограниченный [BACKOFF_CAP].
     * attempt=0 → 1s, 1 → 2s, 2 → 4s, 3 → 8s, 4 → 16s, 5+ → 30s (cap). Суммарно до ~95s за 10 попыток.
     *
     * `internal` — покрывается unit-тестом на чистой формуле (без HTTP/реального delay).
     */
    internal fun backoffDelay(attempt: Int): Long {
        val raw = (1L shl attempt) * BASE_DELAY_MS   // 2^attempt × 1000
        return min(raw, BACKOFF_CAP_MS)
    }

    private companion object {
        /** Максимум попыток на один LLM-вызов (первая + до 9 ретраев). */
        const val MAX_ATTEMPTS = 10
        /** База экспоненциального backoff (attempt=0 → 1s). */
        const val BASE_DELAY_MS = 1_000L
        /** Потолок задержки между попытками. */
        const val BACKOFF_CAP_MS = 30_000L
    }
}

/**
 * Default HttpClient для [OpenAiCompatibleClient] — идентичен бывшему приватному полю (дней 1–29),
 * 0 регрессий wire-формата/timeout. Top-level функция (не member) — чтобы default-параметр
 * конструктора мог на неё ссылаться; инстанцируется один раз на клиент (не на запрос).
 */
private fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            coerceInputValues = true
        })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 120_000
    }
}
