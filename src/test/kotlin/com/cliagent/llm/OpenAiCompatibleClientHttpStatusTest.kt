package com.cliagent.llm

import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 33 (багфикс): тесты на корректную обработку HTTP-ответов 4xx/5xx в [OpenAiCompatibleClient.executeOnce].
 *
 * **Регрессия, которую покрывают эти тесты:** defaultHttpClient не выставляет `expectSuccess = true`,
 * поэтому Ktor НЕ бросает ClientRequestException при 4xx/5xx — response возвращается как есть.
 * Раньше код игнорировал статус и сразу парсил body → 4xx-ответ с `{"error":...}` давал parse-ошибку
 * с `code=0` → `isRetryable(0)=true` → **10 бессмысленных ретраев одного и того же 401**.
 *
 * После багфикса: статус проверяется явно, возвращается [LlmResult.Error] с правильным HTTP-кодом
 * → 4xx non-retryable (без задержек), 5xx retryable, сообщение человекочитаемое.
 *
 * Покрывает:
 *  - 401 с телом `{"error":...}` → Error(401, "API key invalid or expired...") без parse-маскировки
 *  - 429 rate limit → Error(429, ...) — retryable
 *  - 500 server error → Error(500, ...) — retryable
 *  - 200 OK с мусорным body → Error(422, "parse...") — non-retryable (не бесконечный retry как раньше)
 *  - Сообщение об ошибке содержит body сервера (для диагностики)
 */
class OpenAiCompatibleClientHttpStatusTest {

    private val testRequest = ChatRequest(
        model = "test-model",
        messages = listOf(ChatMessage(role = "user", content = "hi")),
    )

    /** Сборка клиента с MockEngine, отвечающим заданным телом/статусом. */
    private fun clientWith(
        status: HttpStatusCode,
        body: String,
        apiKey: String = "fake-key",
    ): OpenAiCompatibleClient {
        val mockEngine = MockEngine { _ ->
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
            }
            install(HttpTimeout)
        }
        return OpenAiCompatibleClient(
            baseUrl = "https://api.test.example",
            apiKey = apiKey,
            httpClient = httpClient,
        )
    }

    @Test
    fun `401 Unauthorized returns Error with code 401 not parse-error`() = runTest {
        // z.ai реальный формат: {"error":{"code":"401","message":"token expired or incorrect"}}
        val client = clientWith(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":{"code":"401","message":"token expired or incorrect"}}""",
        )
        val result = client.chat(testRequest)
        assertTrue(result is LlmResult.Error, "должен быть Error")
        val err = result as LlmResult.Error
        assertEquals(401, err.code, "код должен быть 401, не 0 (parse-маскировка)")
        assertFalse(err.code == 0, "регрессия: code=0 означает parse-fallback")
        // Сообщение должно быть человекочитаемым и содержать тело сервера для диагноза.
        assertTrue(err.message.contains("401") || err.message.contains("Unauthorized", ignoreCase = true),
            "сообщение должно объяснить 401: ${err.message}")
    }

    @Test
    fun `429 Rate Limit returns Error with code 429 - retryable`() = runTest {
        val client = clientWith(
            status = HttpStatusCode.TooManyRequests,
            body = """{"error":{"message":"rate limit"}}""",
        )
        val result = client.chat(testRequest)
        assertTrue(result is LlmResult.Error)
        assertEquals(429, (result as LlmResult.Error).code, "429 должен пройти как 429, не маскироваться")
    }

    @Test
    fun `500 Server Error returns Error with code 500 - retryable`() = runTest {
        val client = clientWith(
            status = HttpStatusCode.InternalServerError,
            body = """{"error":"internal"}""",
        )
        val result = client.chat(testRequest)
        assertTrue(result is LlmResult.Error)
        assertEquals(500, (result as LlmResult.Error).code)
    }

    @Test
    fun `200 OK with unparseable body returns Error 422 - not retried as network error`() = runTest {
        // Регрессия: раньше это давало code=0 → isRetryable(0)=true → 10 ретраев мусора.
        val client = clientWith(
            status = HttpStatusCode.OK,
            body = "this is not JSON at all",
        )
        val result = client.chat(testRequest)
        assertTrue(result is LlmResult.Error)
        val err = result as LlmResult.Error
        assertEquals(422, err.code, "parse-error должен быть 422 (non-retryable), не 0 (retryable)")
        assertFalse(client.isRetryable(err.code), "422 не должен быть retryable")
    }

    @Test
    fun `error message includes server body for diagnosis`() = runTest {
        // Критично для понимания причины: пользователь должен видеть body сервера в ошибке.
        val client = clientWith(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":{"message":"token expired or incorrect"}}""",
        )
        val result = client.chat(testRequest)
        assertTrue(result is LlmResult.Error)
        val msg = (result as LlmResult.Error).message
        // Сообщение должно содержать фрагмент тела сервера, чтобы пользователь видел причину.
        assertTrue(msg.contains("token expired") || msg.contains("token expired or incorrect"),
            "body сервера должен быть в сообщении: $msg")
    }

    @Test
    fun `401 without apiKey gives actionable hint`() = runTest {
        // Если apiKey пустой — сообщение должно подсказать, что нужно CLI_AGENT_API_KEY.
        val client = clientWith(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":"unauthorized"}""",
            apiKey = "",   // симулируем запуск без ключа
        )
        val result = client.chat(testRequest)
        assertTrue(result is LlmResult.Error)
        val err = result as LlmResult.Error
        assertEquals(401, err.code)
        assertTrue(
            err.message.contains("API_KEY", ignoreCase = true) || err.message.contains("Unauthorized", ignoreCase = true),
            "сообщение должно подсказать про CLI_AGENT_API_KEY: ${err.message}"
        )
    }
}
