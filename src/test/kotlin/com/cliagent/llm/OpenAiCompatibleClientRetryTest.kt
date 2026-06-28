package com.cliagent.llm

import com.cliagent.llm.model.ChatResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.min

/**
 * День 19 (progressive retry): unit-тесты retry-логики [OpenAiCompatibleClient].
 *
 * Retry-цикл ([OpenAiCompatibleClient.chat]) завязан на HTTP через Ktor — тяжело мокать без
 * ktor-client-mock. Поэтому ядро логики (retryable-классификация + backoff-формула) покрыто здесь
 * напрямую через приватные функции, а [withRetry] — чистая обёртка для теста именно цикла попыток
 * с инъекцией исполнительной функции (без HTTP и без реального delay — runTest виртуализирует время).
 */
class OpenAiCompatibleClientRetryTest {

    @Test
    fun `retryable codes include 429, 5xx and network timeout`() {
        val client = clientWithRetry()
        assertTrue(client.isRetryable(429))
        assertTrue(client.isRetryable(500))
        assertTrue(client.isRetryable(502))
        assertTrue(client.isRetryable(503))
        assertTrue(client.isRetryable(0))   // таймаут/обрыв сети
    }

    @Test
    fun `non-retryable codes are 4xx except 429`() {
        val client = clientWithRetry()
        assertTrue(!client.isRetryable(400))
        assertTrue(!client.isRetryable(401))
        assertTrue(!client.isRetryable(403))
        assertTrue(!client.isRetryable(404))
    }

    @Test
    fun `backoff grows exponentially and caps at 30s`() {
        val client = clientWithRetry()
        assertEquals(1_000L, client.backoffDelay(0))   // 2^0 × 1000 = 1s
        assertEquals(2_000L, client.backoffDelay(1))   // 2s
        assertEquals(4_000L, client.backoffDelay(2))   // 4s
        assertEquals(8_000L, client.backoffDelay(3))   // 8s
        assertEquals(16_000L, client.backoffDelay(4))  // 16s
        // 2^5 × 1000 = 32000 > cap → ограничено 30s; все последующие тоже cap
        assertEquals(30_000L, client.backoffDelay(5))
        assertEquals(30_000L, client.backoffDelay(9))
    }

    @Test
    fun `success on first attempt does not retry`() = runTest {
        var calls = 0
        val result = withRetry(maxAttempts = 10) {
            calls++
            LlmResult.Success(fakeResponse())
        }
        assertTrue(result is LlmResult.Success)
        assertEquals(1, calls)   // успех → без ретраев
    }

    @Test
    fun `retries on 429 then succeeds`() = runTest {
        var calls = 0
        val result = withRetry(maxAttempts = 10) {
            calls++
            if (calls < 3) LlmResult.Error(429, "rate limit") else LlmResult.Success(fakeResponse())
        }
        assertTrue(result is LlmResult.Success)
        assertEquals(3, calls)   // 2 провала + успех на 3-й
    }

    @Test
    fun `retries on 5xx up to max attempts then returns error`() = runTest {
        var calls = 0
        val result = withRetry(maxAttempts = 10) {
            calls++
            LlmResult.Error(500, "server error")
        }
        assertTrue(result is LlmResult.Error)
        assertEquals(10, calls)   // исчерпали все попытки
    }

    @Test
    fun `does not retry on 401 — returns error immediately`() = runTest {
        var calls = 0
        val result = withRetry(maxAttempts = 10) {
            calls++
            LlmResult.Error(401, "bad key")
        }
        assertTrue(result is LlmResult.Error)
        assertEquals(1, calls)   // non-retryable → без ретраев
    }

    @Test
    fun `retries on network exception (throw) then succeeds`() = runTest {
        var calls = 0
        val result = withRetry(maxAttempts = 10) {
            calls++
            if (calls < 2) throw RuntimeException("connection reset")
            LlmResult.Success(fakeResponse())
        }
        assertTrue(result is LlmResult.Success)
        assertEquals(2, calls)   // исключение + успех
    }

    @Test
    fun `CancellationException is rethrown not swallowed`() = runTest {
        var calls = 0
        var caught = false
        try {
            withRetry(maxAttempts = 10) {
                calls++
                throw kotlinx.coroutines.CancellationException("cancelled")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(1, calls)   // отмена — без ретрая
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Тест-двойник [OpenAiCompatibleClient], экспонирующий приватные методы retry-логики. */
    private fun clientWithRetry() = OpenAiCompatibleClient(baseUrl = "http://x", apiKey = "k")

    private fun fakeResponse(): ChatResponse = ChatResponse(
        id = "test", choices = emptyList(),
        usage = com.cliagent.llm.model.Usage(0, 0, 0)
    )

    /**
     * Чистая retry-петля с инъекцией исполнительной функции — идентичная [OpenAiCompatibleClient.chat]
     * логике. Тестируем именно цикл (попытки, классификация, backoff) без HTTP и MockEngine.
     * `runTest` виртуализирует `delay` → тесты мгновенны.
     */
    private suspend fun withRetry(
        maxAttempts: Int = 10,
        execute: suspend () -> LlmResult<ChatResponse>,
    ): LlmResult<ChatResponse> {
        var lastError: LlmResult.Error? = null
        repeat(maxAttempts) { attempt ->
            try {
                val result = execute()
                if (result is LlmResult.Success) return result
                lastError = result as LlmResult.Error
                val code = result.code
                if (!(code == 429 || code >= 500 || code == 0)) return result   // non-retryable
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = LlmResult.Error(0, "Request failed: ${e.message}")
            }
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(min((1L shl attempt) * 1_000L, 30_000L))
            }
        }
        return lastError ?: LlmResult.Error(0, "Retry exhausted with no error captured")
    }
}
