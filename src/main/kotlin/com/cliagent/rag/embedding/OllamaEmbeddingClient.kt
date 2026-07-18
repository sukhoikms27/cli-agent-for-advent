package com.cliagent.rag.embedding

import com.cliagent.llm.LlmResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * День 21: локальный эмбеддинг-клиент поверх **Ollama**. Лекция недели 5: «Ollama — рекомендуемый
 * инструмент для локальных эмбеддингов: бесплатно, приватно, данные не покидают компьютер».
 *
 * Эндпоинт (актуальный): `POST {baseUrl}/api/embed` с телом `{"model":"nomic-embed-text","input":[...]}`.
 * Ответ: `{"embeddings":[[768 floats],...]}` — по вектору на входной текст, в том же порядке.
 * (Устаревший `/api/embeddings` (singular, `"prompt"`) — deprecated; используем новый batched.)
 *
 * Retry по образцу [com.cliagent.llm.OpenAiCompatibleClient]: до [MAX_ATTEMPTS], экспоненциальный
 * backoff, ретрай только транзиентных (429/5xx/сеть). Митигация известного бага nomic-embed-text
 * (Ollama #12585, ~50% fail) — тот же retry-цикл. `CancellationException` не глотается.
 *
 * DI-шов: [http] инжектируется (default — CIO + ContentNegotiation/json). В тестах подставляется
 * `HttpClient(MockEngine{…})` — без реальной Ollama.
 *
 * **День 32 (remote RAG для CI):** [baseUrl] + [bearerToken] позволяют ходить в удалённую Ollama
 * через reverse-proxy с bearer-auth (Caddy на VPS: `/api/embed` только, `/api/chat` отшит). В CI
 * GitHub передаётся через env `CLI_AGENT_RAG_EMBEDDING_URL` + `CLI_AGENT_RAG_EMBEDDING_TOKEN`.
 *
 * @param baseUrl  адрес Ollama ("http://localhost:11434"; для remote — URL Caddy `http://vps:11435")
 * @param model    имя модели ("nomic-embed-text", 768 dim)
 * @param bearerToken опц. bearer для reverse-proxy с auth (null = без auth, для localhost)
 * @param http     injectable HttpClient (default CIO)
 * @param batchSize число текстов на один HTTP-запрос (Ollama batched; 32 — баланс скорости/памяти)
 * @param ownsClient закрывать ли [http] в [close] (true для default-клиента, false для injected)
 */
class OllamaEmbeddingClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text",
    private val bearerToken: String? = null,
    private val http: HttpClient = defaultClient(),
    private val batchSize: Int = 32,
    private val ownsClient: Boolean = true,
) : EmbeddingClient, AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    override val modelName: String get() = model

    /**
     * Размерность вектора модели. nomic-embed-text → 768. Берём из имени (запрос «пустышки»
     * при construction-time = лишний сетевой round-trip на старте REPL); если модель другая —
     * фактическую размерность всё равно покажет первый ответ, и она persist'ится в [com.cliagent.rag.RagIndex].
     */
    override val dimension: Int = when {
        model.contains("nomic", ignoreCase = true) -> 768
        model.contains("mxbai", ignoreCase = true) -> 1024
        model.contains("minilm", ignoreCase = true) -> 384
        model.contains("bge-m3", ignoreCase = true) -> 1024
        else -> 768
    }

    override suspend fun embed(texts: List<String>): LlmResult<List<List<Float>>> {
        if (texts.isEmpty()) return LlmResult.Success(emptyList())
        val result = mutableListOf<List<Float>>()
        for (batch in texts.chunked(batchSize)) {
            val batchResult = embedWithRetry(batch)
            if (batchResult is LlmResult.Error) return batchResult
            result.addAll((batchResult as LlmResult.Success).data)
        }
        return LlmResult.Success(result)
    }

    /** Один batched-запрос + retry-цикл (транзиентные ошибки). */
    private suspend fun embedWithRetry(batch: List<String>): LlmResult<List<List<Float>>> {
        var lastError: LlmResult.Error? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val r = embedOnce(batch)
                if (r is LlmResult.Success) return r
                lastError = r as LlmResult.Error
                if (!isRetryable(r.code)) return r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = LlmResult.Error(0, "Embedding request failed: ${e.message}")
            }
            if (attempt < MAX_ATTEMPTS - 1) {
                val backoff = backoffDelay(attempt)
                delay(backoff)
            }
        }
        return lastError ?: LlmResult.Error(0, "Retry exhausted with no error captured")
    }

    /** Один HTTP-вызов к Ollama без retry. */
    private suspend fun embedOnce(batch: List<String>): LlmResult<List<List<Float>>> {
        val body = json.encodeToString(EmbedRequest.serializer(), EmbedRequest(model = model, input = batch))
        val response = try {
            http.post("$baseUrl/api/embed") {
                contentType(ContentType.Application.Json)
                // День 32: bearer-auth для remote Ollama через reverse-proxy (null = localhost без auth).
                if (!bearerToken.isNullOrBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $bearerToken")
                }
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ollama не запущена / сеть недоступна → понятное сообщение
            return LlmResult.Error(
                69, // POSIX UNAVAILABLE
                "Ollama недоступна на $baseUrl. Запустите: ollama serve && ollama pull $model"
            )
        }
        val text = response.bodyAsText()
        return try {
            val parsed = json.decodeFromString<EmbedResponse>(text)
            // КРИТИЧНО: Ollama при ошибке модели (CUDA crash, модель не загружена) возвращает HTTP 200
            // с телом {"error":"..."}. Без этой проверки поле error игнорируется (ignoreUnknownKeys=true),
            // embeddings=default emptyList → код трактовал краш как «успех с 0 векторами» → индекс
            // сохранялся пустым, retrieval ничего не находил. Теперь это явная ошибка.
            val err = parsed.error
            if (!err.isNullOrBlank()) {
                LlmResult.Error(69, "Ollama error: $err")
            } else if (parsed.embeddings.size != batch.size) {
                LlmResult.Error(69, "Ollama вернула ${parsed.embeddings.size} векторов, ожидалось ${batch.size}")
            } else {
                LlmResult.Success(parsed.embeddings)
            }
        } catch (e: Exception) {
            LlmResult.Error(response.status.value, "Failed to parse Ollama response: ${e.message}")
        }
    }

    /** Транзиентная ли ошибка (reuse классификации из OpenAiCompatibleClient). */
    internal fun isRetryable(code: Int): Boolean = code == 429 || code >= 500 || code == 0

    /** Экспоненциальный backoff: 2^attempt × BASE, capped. */
    internal fun backoffDelay(attempt: Int): Long {
        val raw = (1L shl attempt) * BASE_DELAY_MS
        return min(raw, BACKOFF_CAP_MS)
    }

    override fun close() {
        if (ownsClient) runCatching { http.close() }
    }

    @Serializable
    private data class EmbedRequest(
        val model: String,
        val input: List<String>,
    )

    @Serializable
    private data class EmbedResponse(
        val model: String = "",
        val embeddings: List<List<Float>> = emptyList(),
        @SerialName("total_duration") val totalDuration: Long? = null,
        val error: String? = null,
    )

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val BASE_DELAY_MS = 1_000L
        const val BACKOFF_CAP_MS = 16_000L
    }
}

/** Default HttpClient для эмбеддингов — копия паттерна WeatherClient/OpenAiCompatibleClient. */
private fun defaultClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 120_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 120_000
    }
}
