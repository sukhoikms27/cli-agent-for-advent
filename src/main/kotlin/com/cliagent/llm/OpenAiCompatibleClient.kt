package com.cliagent.llm

import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.math.min

class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String
) : LlmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        // День 17: при tool_calls ответ LLM может содержать "content": null. content — non-null
        // (String = "") → coerceInputValues превращает null в default ("") вместо ошибки парсинга.
        coerceInputValues = true
    }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
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
     * Один HTTP-вызов к LLM без retry. Возвращает [LlmResult]: Success с распарсенным ответом или
     * Error с кодом/сообщением (HTTP 4xx/5xx, таймаут, мусорный ответ).
     */
    private suspend fun executeOnce(request: ChatRequest): LlmResult<ChatResponse> = try {
        val response: HttpResponse = httpClient.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        val bodyText = response.bodyAsText()
        try {
            LlmResult.Success(json.decodeFromString<ChatResponse>(bodyText))
        } catch (e: Exception) {
            System.err.println("[DEBUG] Raw API response:\n$bodyText")
            LlmResult.Error(0, "Failed to parse response: ${e.message}")
        }
    } catch (e: ClientRequestException) {
        val statusCode = e.response.status.value
        val errorBody = runCatching { e.response.bodyAsText() }.getOrDefault("")
        val message = when (statusCode) {
            401 -> "Invalid API key. Check CLI_AGENT_API_KEY environment variable."
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
