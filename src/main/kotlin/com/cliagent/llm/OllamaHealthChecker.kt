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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * День 26: health-check локальной **Ollama** для команды `/local`. Говорит на native API Ollama
 * (`/api/tags`, `/api/chat`), а НЕ на OpenAI-compat (`/v1/...`) — native endpoint отдаёт больше
 * метаинформации (список моделей с размером/квантизацией/параметрами), нужной для `/local status`.
 *
 * Паттерн HTTP повторяет [com.cliagent.rag.embedding.OllamaEmbeddingClient] (Ktor HttpClient +
 * `withContext(Dispatchers.IO)` + CancellationException не глотается). DI-шов [http] — для
 * MockEngine-тестов без реальной Ollama.
 *
 * Базовый URL — **native** (без `/v1` суффикса), т.е. `http://localhost:11434`. Конфиг приложения
 * хранит OpenAI-compat базу (`http://localhost:11434/v1`); конвертация — через [nativeBaseFrom].
 *
 * @param baseUrl native Ollama base (без /v1); default `http://localhost:11434`.
 * @param http    injectable HttpClient (default CIO). Закрывается в [close] только если ownsClient.
 * @param ownsClient закрывать ли [http] в [close] (true для default-клиента, false для injected).
 */
class OllamaHealthChecker(
    private val baseUrl: String = "http://localhost:11434",
    private val http: HttpClient = defaultClient(),
    private val ownsClient: Boolean = true,
) : AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Проверяет достижимость Ollama и возвращает список установленных моделей.
     *
     * `GET {baseUrl}/api/tags` → `{"models":[{"name":"qwen3:14b","size":..., "details":{...}}, ...]}`.
     * Успех → [OllamaHealth] с reachable=true и распарсенными моделями. Любая ошибка (сеть/парсинг)
     * → reachable=false с понятным сообщением. `CancellationException` re-throw (AGENTS.md).
     */
    suspend fun checkHealth(): OllamaHealth = withContext(Dispatchers.IO) {
        val response = try {
            http.get("$baseUrl/api/tags")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext OllamaHealth(
                reachable = false,
                models = emptyList(),
                errorMessage = "Ollama недоступна на $baseUrl: ${e.message ?: e.javaClass.simpleName}"
            )
        }
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext OllamaHealth(
                reachable = false,
                models = emptyList(),
                errorMessage = "Не удалось прочитать ответ Ollama: ${e.message}"
            )
        }
        val parsed = try {
            json.decodeFromString<TagsResponse>(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Ollama иногда возвращает {"error":"..."} на крашах — пытаемся вытащить.
            val errMsg = runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()
            return@withContext OllamaHealth(
                reachable = false,
                models = emptyList(),
                errorMessage = errMsg ?: "Не удалось разобрать ответ Ollama /api/tags: ${e.message}"
            )
        }
        // Ollama при краше/проблемах может вернуть HTTP 200 с {"error":"..."} (как у /api/embed).
        // Без этой проверки error-поле игнорируется (ignoreUnknownKeys=true) → маскировалось как
        // reachable=true с пустым списком моделей. Теперь это явная недоступность.
        if (!parsed.error.isNullOrBlank()) {
            return@withContext OllamaHealth(
                reachable = false,
                models = emptyList(),
                errorMessage = parsed.error,
            )
        }
        OllamaHealth(
            reachable = true,
            models = parsed.models.map { it.toInfo() },
            errorMessage = null,
        )
    }

    /**
     * Проверяет, что конкретная [model] может ответить (загружается и генерирует). Шлём минимальный
     * `ping` с `num_predict=5` (быстро, дёшево). true — модель откликнулась; false — ошибка/нет ответа.
     *
     * `POST {baseUrl}/api/chat` с `{"model":...,"messages":[...],"stream":false,"options":{"num_predict":5}}`.
     */
    suspend fun ping(model: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(
            PingRequest.serializer(),
            PingRequest(model = model, messages = listOf(PingMessage(role = "user", content = "ping")))
        )
        val response = try {
            http.post("$baseUrl/api/chat") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext false
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext false
        }
        // Успех = распарсили и нет поля error. Достаточно наличия валидного JSON-ответа.
        val parsed = runCatching { json.decodeFromString<PingResponse>(text) }.getOrNull()
            ?: return@withContext false
        parsed.error.isNullOrBlank()
    }

    override fun close() {
        if (ownsClient) runCatching { http.close() }
    }

    // ── response DTOs ──────────────────────────────────────────────────────────

    @Serializable
    private data class TagsResponse(
        val models: List<TagModel> = emptyList(),
        val error: String? = null,
    )

    @Serializable
    private data class TagModel(
        val name: String = "",
        val model: String = "",
        val size: Long = 0,
        val details: ModelDetails? = null,
    )

    @Serializable
    private data class ModelDetails(
        @SerialName("quantization_level") val quantizationLevel: String? = null,
        @SerialName("parameter_size") val parameterSize: String? = null,
        val family: String? = null,
    )

    @Serializable
    private data class ErrorResponse(val error: String? = null)

    @Serializable
    private data class PingRequest(
        val model: String,
        val messages: List<PingMessage>,
        val stream: Boolean = false,
        val options: PingOptions = PingOptions(),
    )

    @Serializable
    private data class PingMessage(val role: String, val content: String)

    @Serializable
    private data class PingOptions(
        @SerialName("num_predict") val numPredict: Int = 5,
    )

    @Serializable
    private data class PingResponse(
        val model: String = "",
        val message: PingMessage? = null,
        val error: String? = null,
    )

    private fun TagModel.toInfo(): OllamaModelInfo = OllamaModelInfo(
        name = name.ifBlank { model },
        sizeBytes = size,
        quantization = details?.quantizationLevel,
        parameterSize = details?.parameterSize,
    )
}

/**
 * Сводный результат health-check Ollama.
 *
 * @param reachable отвечает ли Ollama на /api/tags.
 * @param models список установленных моделей (пусто, если unreachable).
 * @param errorMessage человекочитаемая причина недоступности (null при reachable=true).
 */
data class OllamaHealth(
    val reachable: Boolean,
    val models: List<OllamaModelInfo>,
    val errorMessage: String?,
)

/**
 * Описание одной установленной модели Ollama (из /api/tags).
 *
 * @param name тег модели ("qwen3:14b").
 * @param sizeBytes размер на диске (байты).
 * @param quantization уровень квантизации ("q4_K_M") — null, если Ollama не отдала.
 * @param parameterSize размер параметров ("14B") — null, если Ollama не отдала.
 */
data class OllamaModelInfo(
    val name: String,
    val sizeBytes: Long,
    val quantization: String?,
    val parameterSize: String?,
)

/**
 * Конвертирует OpenAI-compat base URL Ollama в native base (без `/v1`).
 *
 * Конфиг приложения хранит `http://localhost:11434/v1` (для OpenAI-compat endpoint
 * `/v1/chat/completions`), но native API Ollama живёт на корне (`/api/tags`). Эта функция
 * убирает trailing `/v1` или `/v1/`:
 *  - `http://localhost:11434/v1` → `http://localhost:11434`
 *  - `http://localhost:11434/v1/` → `http://localhost:11434`
 *  - `http://localhost:11434` → без изменений (уже native)
 *  - `https://api.z.ai/.../v4` → без изменений (нет /v1 суффикса)
 */
fun nativeBaseFrom(openAiCompatBaseUrl: String): String {
    var s = openAiCompatBaseUrl.trimEnd('/')
    // Убираем ровно trailing /v1 (если это суффикс пути).
    if (s.endsWith("/v1", ignoreCase = true)) {
        s = s.dropLast(3)
    }
    return s
}

/** Default HttpClient для health-check — копия паттерна OllamaEmbeddingClient. */
private fun defaultClient(): HttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 30_000
    }
}
