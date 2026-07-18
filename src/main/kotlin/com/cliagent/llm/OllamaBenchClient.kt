package com.cliagent.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * День 31: benchmarking-клиент для локальной Ollama. Не реализует [LlmClient] — это не LLM-клиент,
 * а метрик-сборщик для `/rag compare-local`: VRAM-использование (`/api/ps`) и tokens/sec (`/api/chat`
 * с замером eval_duration). Симметричен [OllamaHealthChecker] (injectable HttpClient, свой Json,
 * Dispatchers.IO, CancellationException re-throw).
 *
 * Метрики нужны для side-by-side сравнения local vs cloud: VRAM показывает «сколько памяти съедает
 * модель», tokens/sec — реальную throughput (важнее latency для длинных ответов thinking-моделей).
 *
 * Все методы возвращают `null` при ошибке (сеть/парсинг/пустой ответ) — graceful degradation:
 * compare-local продолжается без метрик, если бенчмаркинг недоступен.
 *
 * @param baseUrl native Ollama base (без `/v1`), т.е. `http://localhost:11434`.
 * @param http injectable HttpClient (default CIO). Закрывается в [close] только если [ownsClient].
 * @param ownsClient закрывать ли [http] в [close].
 */
class OllamaBenchClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultClient(),
    private val ownsClient: Boolean = true,
) : AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true   // stream=false должен попасть в wire (как в OllamaNativeClient)
    }

    /**
     * Снимок VRAM-использования загруженных моделей: `GET {baseUrl}/api/ps`. Суммирует `size_vram`
     * всех моделей в bytes → делит на 1_048_576 (MB). null при ошибке/пустом списке/нулевом VRAM.
     *
     * `/api/ps` возвращает `{"models":[{"name":"qwen3:14b","size_vram":14000000000,...}, ...]}`.
     * Модели не в VRAM (выгружены) имеют `size_vram: 0` → в сумму не вносят вклада.
     */
    suspend fun snapshotVram(): Long? = withContext(Dispatchers.IO) {
        val response = try {
            http.get("$baseUrl/api/ps")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext null
        }
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext null
        }
        val parsed = try {
            json.decodeFromString<PsResponse>(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext null
        }
        if (parsed.models.isEmpty()) return@withContext null
        val totalBytes = parsed.models.sumOf { it.sizeVram }
        if (totalBytes <= 0) return@withContext null
        totalBytes / BYTES_PER_MB
    }

    /**
     * Замер tokens/sec: `POST {baseUrl}/api/chat` с `stream:false` и минимальным `num_predict`. Ollama
     * возвращает `eval_count` (сгенерированные токены) и `eval_duration` (наносекунды генерации).
     * Throughput = `eval_count / (eval_duration / 1e9)` = токенов в секунду.
     *
     * null если: ошибка сети/парсинга, error-поле в ответе, отсутствуют `eval_count`/`eval_duration`,
     * или eval_duration=0 (деление на ноль). Минимальный num_predict (~16) делает замер быстрым, но
     * даёт стабильную throughput-оценку (модель уже загружена после первого токена).
     *
     * @param model имя модели (qwen3:14b).
     * @param systemPrompt system-сообщение (для контекста; влияет слабо на throughput).
     * @param userPrompt user-промпт для генерации.
     */
    suspend fun measureTokensPerSec(model: String, systemPrompt: String, userPrompt: String): Double? =
        withContext(Dispatchers.IO) {
            val request = OllamaBenchChatRequest(
                model = model,
                messages = listOf(
                    OllamaBenchMessage(role = "system", content = systemPrompt),
                    OllamaBenchMessage(role = "user", content = userPrompt),
                ),
                stream = false,
                options = OllamaBenchOptions(numPredict = BENCH_NUM_PREDICT, numCtx = BENCH_NUM_CTX),
            )
            val body = json.encodeToString(OllamaBenchChatRequest.serializer(), request)
            val response = try {
                http.post("$baseUrl/api/chat") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withContext null
            }
            val text = try {
                response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withContext null
            }
            val parsed = try {
                json.decodeFromString<OllamaBenchChatResponse>(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return@withContext null
            }
            if (!parsed.error.isNullOrBlank()) return@withContext null
            val evalCount = parsed.evalCount ?: return@withContext null
            val evalDurationNs = parsed.evalDuration ?: return@withContext null
            if (evalDurationNs <= 0) return@withContext null
            val seconds = evalDurationNs / NANOS_PER_SEC
            evalCount.toDouble() / seconds
        }

    override fun close() {
        if (ownsClient) runCatching { http.close() }
    }

    // ── wire DTOs (private, симметрично OllamaHealthChecker) ────────────────────

    @Serializable
    private data class PsResponse(
        val models: List<PsModel> = emptyList(),
    )

    @Serializable
    private data class PsModel(
        val name: String = "",
        @SerialName("size_vram") val sizeVram: Long = 0,
    )

    @Serializable
    private data class OllamaBenchChatRequest(
        val model: String,
        val messages: List<OllamaBenchMessage>,
        val stream: Boolean = false,
        val options: OllamaBenchOptions,
    )

    @Serializable
    private data class OllamaBenchMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class OllamaBenchOptions(
        @SerialName("num_predict") val numPredict: Int,
        @SerialName("num_ctx") val numCtx: Int,
    )

    @Serializable
    private data class OllamaBenchChatResponse(
        @SerialName("eval_count") val evalCount: Int? = null,
        @SerialName("eval_duration") val evalDuration: Long? = null,
        val error: String? = null,
    )

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
        const val NANOS_PER_SEC = 1_000_000_000.0
        /** Минимальный num_predict для стабильной throughput-оценки (быстро, но не тривиально-коротко). */
        const val BENCH_NUM_PREDICT = 16
        /** Context window для bench-запроса (достаточно для system+user prompt). */
        const val BENCH_NUM_CTX = 4096
    }
}

/** Default HttpClient — копия паттерна OllamaHealthChecker. */
private fun defaultClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 60_000
    }
}
