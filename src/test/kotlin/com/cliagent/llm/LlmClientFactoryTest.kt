package com.cliagent.llm

import com.cliagent.config.AppConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 25 (multi-provider support): unit-тесты [LlmProvider] (дискриминатор) и [LlmClientFactory]
 * (dispatch seam). Без HTTP — фабрика только резолвит провайдер и инстанцирует клиент; проверки
 * auth-логики (requiresApiKey + пустой ключ) на чистых данных конфига.
 */
class LlmClientFactoryTest {

    // ── LlmProvider.fromString ────────────────────────────────────────────────

    @Test
    fun `fromString ollama resolves to OLLAMA without api key requirement`() {
        assertEquals(LlmProvider.OLLAMA, LlmProvider.fromString("ollama"))
        assertTrue(!LlmProvider.OLLAMA.requiresApiKey())
    }

    @Test
    fun `fromString zai resolves to ZAI with api key requirement`() {
        assertEquals(LlmProvider.ZAI, LlmProvider.fromString("zai"))
        assertTrue(LlmProvider.ZAI.requiresApiKey())
    }

    @Test
    fun `fromString null and blank return null for autoDetect fallback`() {
        assertNull(LlmProvider.fromString(null))
        assertNull(LlmProvider.fromString(""))
        assertNull(LlmProvider.fromString("   "))
    }

    @Test
    fun `fromString unknown value falls back to OPENAI_COMPATIBLE`() {
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, LlmProvider.fromString("unknown-thing"))
    }

    @Test
    fun `fromString is case-insensitive and accepts aliases`() {
        // Регистронезависимость.
        assertEquals(LlmProvider.ZAI, LlmProvider.fromString("ZAI"))
        assertEquals(LlmProvider.OLLAMA, LlmProvider.fromString("OLLAMA"))
        // Алиасы Ollama.
        assertEquals(LlmProvider.OLLAMA, LlmProvider.fromString("local"))
        assertEquals(LlmProvider.OLLAMA, LlmProvider.fromString("ollama-local"))
        // Алиасы generic.
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, LlmProvider.fromString("openai"))
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, LlmProvider.fromString("generic"))
    }

    // ── LlmProvider.autoDetect ────────────────────────────────────────────────

    @Test
    fun `autoDetect z_ai URL resolves to ZAI`() {
        assertEquals(LlmProvider.ZAI, LlmProvider.autoDetect("https://api.z.ai/api/coding/paas/v4"))
    }

    @Test
    fun `autoDetect localhost 11434 resolves to OLLAMA`() {
        assertEquals(LlmProvider.OLLAMA, LlmProvider.autoDetect("http://localhost:11434/v1"))
        assertEquals(LlmProvider.OLLAMA, LlmProvider.autoDetect("http://127.0.0.1:11434/v1"))
    }

    @Test
    fun `autoDetect openai URL resolves to OPENAI_COMPATIBLE`() {
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, LlmProvider.autoDetect("https://api.openai.com/v1"))
    }

    // ── LlmClientFactory.create ───────────────────────────────────────────────

    @Test
    fun `create with ollama provider returns OllamaNativeClient without api key`() {
        val config = AppConfig(
            baseUrl = "http://localhost:11434/v1",
            provider = "ollama",
            apiKey = ""
        )
        val client = LlmClientFactory.create(config)
        // День 31: OLLAMA → OllamaNativeClient (native /api/chat, options/keep_alive/think).
        assertInstanceOf(OllamaNativeClient::class.java, client)
        // Native base (без /v1) — закрываем HttpClient после проверки.
        (client as AutoCloseable).close()
    }

    @Test
    fun `create with zai provider returns OpenAiCompatibleClient`() {
        val config = AppConfig(
            provider = "zai",
            apiKey = "key",
            baseUrl = "https://api.z.ai/api/coding/paas/v4"
        )
        val client = LlmClientFactory.create(config)
        // День 31: ZAI/OPENAI_COMPATIBLE → OpenAiCompatibleClient (OpenAI-compat wire).
        assertInstanceOf(OpenAiCompatibleClient::class.java, client)
    }

    @Test
    fun `create with zai provider and empty key throws IllegalStateException`() {
        val config = AppConfig(
            provider = "zai",
            apiKey = "",
            baseUrl = "https://api.z.ai/api/coding/paas/v4"
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            LlmClientFactory.create(config)
        }
        // Подсказка должна упоминать API key.
        assertTrue(ex.message!!.contains("API key", ignoreCase = true))
    }
}
