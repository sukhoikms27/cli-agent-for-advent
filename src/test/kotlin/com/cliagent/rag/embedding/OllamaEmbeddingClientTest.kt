package com.cliagent.rag.embedding

import com.cliagent.llm.LlmResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 21: unit-тесты [OllamaEmbeddingClient] через Ktor MockEngine — без реальной Ollama.
 * Зеркалирует паттерн `WeatherClientTest` (mcp-server): injectable HttpClient с MockEngine.
 */
class OllamaEmbeddingClientTest {

    @Test
    fun `embed parses batched response in order`() = runTest {
        val client = clientWith(
            """{"model":"nomic-embed-text","embeddings":[[0.1,0.2,0.3],[0.4,0.5,0.6]]}"""
        )
        val result = client.embed(listOf("hello", "world"))
        assertTrue(result is LlmResult.Success)
        val vecs = (result as LlmResult.Success).data
        assertEquals(2, vecs.size)
        assertEquals(3, vecs[0].size)
        assertEquals(0.1f, vecs[0][0])
        assertEquals(0.6f, vecs[1][2])
        client.close()
    }

    @Test
    fun `embed returns empty success for empty input`() = runTest {
        val client = clientWith("""{"embeddings":[]}""")
        val result = client.embed(emptyList())
        assertTrue(result is LlmResult.Success)
        assertEquals(0, (result as LlmResult.Success).data.size)
        client.close()
    }

    @Test
    fun `embed reports unavailable when Ollama not running (connection refused)`() = runTest {
        // MockEngine бросает исключение → клиент мапит в Error(69) с понятным сообщением.
        val client = OllamaEmbeddingClient(
            baseUrl = "http://localhost:11434",
            model = "nomic-embed-text",
            http = clientThrowing(),
        )
        val result = client.embed(listOf("q"))
        // Retry цикл перепробует все попытки, затем вернёт последнюю ошибку (code 69).
        assertTrue(result is LlmResult.Error, "expected error, got $result")
        val err = result as LlmResult.Error
        assertTrue(err.message.contains("Ollama") || err.message.contains("недоступна"),
            "expected helpful message, got: ${err.message}")
    }

    @Test
    fun `dimension is 768 for nomic-embed-text`() {
        val client = clientWith("""{"embeddings":[]}""")
        assertEquals(768, client.dimension)
        assertEquals("nomic-embed-text", client.modelName)
        client.close()
    }

    @Test
    fun `retryable classification matches LLM client semantics`() {
        val client = clientWith("""{"embeddings":[]}""")
        assertTrue(client.isRetryable(429))
        assertTrue(client.isRetryable(500))
        assertTrue(client.isRetryable(0))
        assertTrue(!client.isRetryable(400))
        assertTrue(!client.isRetryable(401))
        client.close()
    }

    @Test
    fun `backoff grows exponentially and caps`() {
        val client = clientWith("""{"embeddings":[]}""")
        assertEquals(1_000L, client.backoffDelay(0))
        assertEquals(2_000L, client.backoffDelay(1))
        assertTrue(client.backoffDelay(10) <= 16_000L)
        client.close()
    }

    @Test
    fun `embed handles unparseable response as error`() = runTest {
        // Кривой body → парсинг падает → Error со статусом. 404 = non-retryable → без задержек.
        val client = clientWith(
            """this is not valid json at all""",
            status = HttpStatusCode.NotFound,
        )
        val result = client.embed(listOf("hello"))
        assertTrue(result is LlmResult.Error, "expected error on unparseable body, got $result")
        client.close()
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun clientWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): OllamaEmbeddingClient {
        val mockEngine = MockEngine { _ ->
            respond(body, status, headersOf("Content-Type", "application/json"))
        }
        return OllamaEmbeddingClient(
            baseUrl = "http://localhost:11434",
            model = "nomic-embed-text",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
    }

    private fun clientThrowing(): HttpClient {
        val mockEngine = MockEngine { _ -> throw java.net.ConnectException("Connection refused") }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
    }
}
