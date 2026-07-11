package com.cliagent.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 26: unit-тесты [OllamaHealthChecker] через Ktor MockEngine — без реальной Ollama. Зеркалирует
 * паттерн OllamaEmbeddingClientTest. Покрывает: парсинг /api/tags, connection refused, ping success,
 * helper nativeBaseFrom.
 */
class OllamaHealthCheckerTest {

    @Test
    fun `checkHealth parses valid api_tags response into models`() = runTest {
        val body = """
            {"models":[
              {"name":"qwen3:14b","model":"qwen3:14b","size":8980330752,
               "details":{"quantization_level":"q4_K_M","parameter_size":"14B","family":"qwen3"}},
              {"name":"nomic-embed-text","model":"nomic-embed-text","size":274323967,
               "details":{"quantization_level":"q8_0","parameter_size":"137M","family":"nomic-bert"}}
            ]}
        """.trimIndent()
        val checker = checkerWith(body)
        val health = checker.checkHealth()
        assertTrue(health.reachable)
        assertNull(health.errorMessage)
        assertEquals(2, health.models.size)
        val qwen = health.models[0]
        assertEquals("qwen3:14b", qwen.name)
        assertEquals(8_980_330_752L, qwen.sizeBytes)
        assertEquals("q4_K_M", qwen.quantization)
        assertEquals("14B", qwen.parameterSize)
        checker.close()
    }

    @Test
    fun `checkHealth handles empty models list as reachable`() = runTest {
        val checker = checkerWith("""{"models":[]}""")
        val health = checker.checkHealth()
        assertTrue(health.reachable)
        assertEquals(0, health.models.size)
        checker.close()
    }

    @Test
    fun `checkHealth returns unreachable on connection refused`() = runTest {
        val checker = OllamaHealthChecker(
            baseUrl = "http://localhost:11434",
            http = throwingClient(),
        )
        val health = checker.checkHealth()
        assertFalse(health.reachable)
        assertEquals(0, health.models.size)
        assertTrue(health.errorMessage!!.contains("Ollama") || health.errorMessage!!.contains("недоступна"),
            "expected helpful message, got: ${health.errorMessage}")
        checker.close()
    }

    @Test
    fun `checkHealth surfaces Ollama error field instead of masking as success`() = runTest {
        // Ollama при краше может вернуть {"error":"..."}.
        val checker = checkerWith("""{"error":"internal failure"}""")
        val health = checker.checkHealth()
        assertFalse(health.reachable)
        val msg = health.errorMessage
        assertTrue(msg != null && msg.contains("internal failure"),
            "expected error surfaced, got: $msg")
        checker.close()
    }

    @Test
    fun `ping returns true when chat responds without error`() = runTest {
        val body = """{"model":"qwen3:14b","message":{"role":"assistant","content":"pong"},"done":true}"""
        val checker = checkerWith(body)
        val ok = checker.ping("qwen3:14b")
        assertTrue(ok)
        checker.close()
    }

    @Test
    fun `ping returns false on connection refused`() = runTest {
        val checker = OllamaHealthChecker(
            baseUrl = "http://localhost:11434",
            http = throwingClient(),
        )
        val ok = checker.ping("qwen3:14b")
        assertFalse(ok)
        checker.close()
    }

    @Test
    fun `ping returns false when response carries error field`() = runTest {
        val body = """{"error":"model 'qwen3:14b' not found, try pulling it first"}"""
        val checker = checkerWith(body)
        val ok = checker.ping("qwen3:14b")
        assertFalse(ok)
        checker.close()
    }

    // ── nativeBaseFrom helper ─────────────────────────────────────────────────

    @Test
    fun `nativeBaseFrom strips trailing v1 suffix`() {
        assertEquals("http://localhost:11434", nativeBaseFrom("http://localhost:11434/v1"))
    }

    @Test
    fun `nativeBaseFrom strips trailing v1 slash suffix`() {
        assertEquals("http://localhost:11434", nativeBaseFrom("http://localhost:11434/v1/"))
    }

    @Test
    fun `nativeBaseFrom leaves already-native base unchanged`() {
        assertEquals("http://localhost:11434", nativeBaseFrom("http://localhost:11434"))
    }

    @Test
    fun `nativeBaseFrom does not strip non-v1 versioned path`() {
        assertEquals("https://api.z.ai/api/coding/paas/v4", nativeBaseFrom("https://api.z.ai/api/coding/paas/v4"))
    }

    @Test
    fun `nativeBaseFrom is case-insensitive on v1 suffix`() {
        assertEquals("http://localhost:11434", nativeBaseFrom("http://localhost:11434/V1"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun checkerWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): OllamaHealthChecker {
        val mockEngine = MockEngine { _ ->
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return OllamaHealthChecker(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false })
                }
            },
        )
    }

    private fun throwingClient(): HttpClient {
        val mockEngine = MockEngine { _ -> throw java.net.ConnectException("Connection refused") }
        return HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; explicitNulls = false })
            }
        }
    }
}
