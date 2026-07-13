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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * День 31: unit-тесты [OllamaBenchClient] через Ktor MockEngine — без реальной Ollama. Зеркалирует
 * паттерн [OllamaHealthCheckerTest]. Покрывает snapshotVram (/api/ps), measureTokensPerSec
 * (/api/chat с eval_count/eval_duration) и graceful null при ошибках.
 */
class OllamaBenchClientTest {

    @Test
    fun `snapshotVram sums size_vram across models and converts to MB`() = runTest {
        // 2 модели: 2_097_152_000 bytes (2000 MB) + 524_288_000 bytes (500 MB) → сумма 2500 MB.
        val body = """
            {"models":[
              {"name":"qwen3:14b","size_vram":2097152000},
              {"name":"nomic-embed-text","size_vram":524288000}
            ]}
        """.trimIndent()
        val client = clientWith(body)
        val vram = client.snapshotVram()
        client.close()

        assertEquals(2500L, vram, "sum of size_vram in MB (2_097_152_000 + 524_288_000) / 1_048_576")
    }

    @Test
    fun `snapshotVram returns null on connection refused`() = runTest {
        val client = OllamaBenchClient(
            baseUrl = "http://localhost:11434",
            http = throwingClient(),
        )
        val vram = client.snapshotVram()
        client.close()
        assertNull(vram, "network error → null (graceful)")
    }

    @Test
    fun `snapshotVram returns null when models list empty`() = runTest {
        val client = clientWith("""{"models":[]}""")
        val vram = client.snapshotVram()
        client.close()
        assertNull(vram, "empty models → null (no VRAM usage)")
    }

    @Test
    fun `snapshotVram returns null when all size_vram zero`() = runTest {
        // Модели выгружены из VRAM (size_vram=0) → нет использования.
        val client = clientWith("""{"models":[{"name":"x","size_vram":0}]}""")
        val vram = client.snapshotVram()
        client.close()
        assertNull(vram, "zero VRAM → null")
    }

    @Test
    fun `measureTokensPerSec computes eval_count over eval_duration seconds`() = runTest {
        // eval_count=100, eval_duration=2_000_000_000 ns (2s) → 100/2 = 50.0 tok/s.
        val body = """{"model":"qwen3:14b","eval_count":100,"eval_duration":2000000000,"done":true}"""
        val client = clientWith(body)
        val tps = client.measureTokensPerSec("qwen3:14b", "sys", "user")
        client.close()

        assertNotNull(tps, "throughput should be measured")
        assertEquals(50.0, tps!!, 0.001, "100 tokens / 2s = 50 tok/s")
    }

    @Test
    fun `measureTokensPerSec returns null when eval_duration missing`() = runTest {
        val body = """{"model":"qwen3:14b","eval_count":100,"done":true}"""
        val client = clientWith(body)
        val tps = client.measureTokensPerSec("qwen3:14b", "sys", "user")
        client.close()
        assertNull(tps, "missing eval_duration → null (cannot compute throughput)")
    }

    @Test
    fun `measureTokensPerSec returns null when error field present`() = runTest {
        val body = """{"error":"model not found"}"""
        val client = clientWith(body)
        val tps = client.measureTokensPerSec("qwen3:14b", "sys", "user")
        client.close()
        assertNull(tps, "error field → null")
    }

    @Test
    fun `measureTokensPerSec returns null on connection refused`() = runTest {
        val client = OllamaBenchClient(
            baseUrl = "http://localhost:11434",
            http = throwingClient(),
        )
        val tps = client.measureTokensPerSec("qwen3:14b", "sys", "user")
        client.close()
        assertNull(tps)
    }

    @Test
    fun `measureTokensPerSec returns null when eval_duration is zero`() = runTest {
        // Деление на ноль должно быть защищено (eval_duration=0 → null, не Inf/NaN).
        val body = """{"model":"qwen3:14b","eval_count":5,"eval_duration":0,"done":true}"""
        val client = clientWith(body)
        val tps = client.measureTokensPerSec("qwen3:14b", "sys", "user")
        client.close()
        assertNull(tps, "eval_duration=0 → null (avoid division by zero)")
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun clientWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): OllamaBenchClient {
        val mockEngine = MockEngine { _ ->
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return OllamaBenchClient(
            baseUrl = "http://localhost:11434",
            http = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true })
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
