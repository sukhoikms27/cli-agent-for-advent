package com.cliagent.cli

import com.cliagent.llm.LlmClient
import com.cliagent.llm.LlmResult
import com.cliagent.llm.model.ChatMessage
import com.cliagent.llm.model.ChatRequest
import com.cliagent.llm.model.ChatResponse
import com.cliagent.llm.model.Choice
import com.cliagent.llm.model.Usage
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * День 26: unit-тесты [LocalSmoke] на stub [LlmClient] (без сети). Проверяем, что harness прогоняет
 * ровно 3 промпта, корректно снимает latency/токены и возвращает 3 результата.
 */
class LocalSmokeTest {

    @Test
    fun `runSmoke executes 3 prompts and returns 3 results with metrics`() = runTest {
        val stub = CountingStubClient()
        val results = LocalSmoke.runSmoke(stub, "qwen3:14b")
        assertEquals(3, results.size)
        assertEquals(3, stub.calls)
        // Каждый ответ помечен индексом промпта (см. stub) — проверяем порядок совпадает.
        assertEquals("answer-1", results[0].response)
        assertEquals("answer-2", results[1].response)
        assertEquals("answer-3", results[2].response)
        // Токены прокинуты из usage stub'а (100/40 на каждый).
        assertEquals(100, results[0].promptTokens)
        assertEquals(40, results[0].completionTokens)
    }

    @Test
    fun `runSmoke records non-negative latency per result`() = runTest {
        val stub = CountingStubClient()
        val results = LocalSmoke.runSmoke(stub, "qwen3:14b")
        results.forEach { assertTrue(it.responseTimeMs >= 0, "latency must be non-negative") }
    }

    @Test
    fun `runSmoke captures LLM error as ERROR result without throwing`() = runTest {
        val stub = FailingStubClient()
        val results = LocalSmoke.runSmoke(stub, "qwen3:14b")
        assertEquals(3, results.size)
        results.forEach {
            assertTrue(it.response.startsWith("ERROR"), "error response must be captured; got ${it.response}")
            assertEquals(0, it.promptTokens)
            assertEquals(0, it.completionTokens)
        }
    }

    @Test
    fun `runSmoke prompts cover arithmetic reasoning and code categories`() = runTest {
        val stub = RecordingStubClient()
        LocalSmoke.runSmoke(stub, "qwen3:14b")
        assertEquals(3, stub.prompts.size)
        // Арифметика
        assertTrue(stub.prompts.any { it.contains("17") && it.contains("23") })
        // Reasoning (инкапсуляция)
        assertTrue(stub.prompts.any { it.contains("инкапсуляци", ignoreCase = true) })
        // Code (палиндром)
        assertTrue(stub.prompts.any { it.contains("палиндром", ignoreCase = true) })
    }

    @Test
    fun `runSmoke sets maxTokens to avoid empty answers from thinking models`() = runTest {
        // День 26: qwen3 thinking-модели тратят токены на <think> до ответа — без явного max_tokens
        // Ollama обрывает рано и content пустой. Smoke обязан передавать достаточный maxTokens.
        val stub = RecordingStubClient()
        LocalSmoke.runSmoke(stub, "qwen3:14b")
        assertEquals(3, stub.maxTokensList.size)
        stub.maxTokensList.forEach {
            assertTrue(it != null && it >= 1024, "maxTokens must be set and sufficient for thinking models; got $it")
        }
    }

    @Test
    fun `runSmoke rethrows CancellationException (never swallowed)`() = runTest {
        val stub = CancellingStubClient()
        var caught = false
        try {
            LocalSmoke.runSmoke(stub, "qwen3:14b")
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = true
        }
        assertTrue(caught)
    }

    // ── stub clients ──────────────────────────────────────────────────────────

    /** Возвращает детерминированный ответ с usage; считает вызовы для проверки кол-ва промптов. */
    private class CountingStubClient : LlmClient {
        var calls = 0
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
            calls++
            val idx = calls
            return LlmResult.Success(
                ChatResponse(
                    id = "r-$idx",
                    choices = listOf(Choice(index = 0, message = ChatMessage(role = "assistant", content = "answer-$idx"))),
                    usage = Usage(promptTokens = 100, completionTokens = 40, totalTokens = 140),
                )
            )
        }
    }

    /** Запоминает прогоненные промпты для проверки покрытия категорий. */
    private class RecordingStubClient : LlmClient {
        val prompts = mutableListOf<String>()
        val maxTokensList = mutableListOf<Int?>()
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> {
            prompts.add(request.messages.firstOrNull { it.role == "user" }?.content ?: "")
            maxTokensList.add(request.maxTokens)
            return LlmResult.Success(
                ChatResponse(id = "r", choices = listOf(Choice(0, ChatMessage("assistant", "ok"))))
            )
        }
    }

    /** Всегда Error — проверка, что harness фиксирует ошибку, не падая. */
    private class FailingStubClient : LlmClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> =
            LlmResult.Error(503, "LLM unavailable")
    }

    /** Бросает CancellationException — проверка, что harness не глотает отмену. */
    private class CancellingStubClient : LlmClient {
        override suspend fun chat(request: ChatRequest): LlmResult<ChatResponse> =
            throw kotlinx.coroutines.CancellationException("cancelled")
    }
}
